def call(Map args = [:]) {
    String containerName = args.get('containerName', 'pixi')
    String gcsBucket = args.get('gcsBucket', 'gs://nicholas-testing')

    stage('Checkout') {
        container(containerName) {
            runStageWithNotification('Checkout') {
                sh '''#!/usr/bin/env bash
set -euo pipefail

if [ -n "${BRANCH}" ]; then
  git clone --branch "${BRANCH}" "${REPO_URL}" repo
else
  git clone "${REPO_URL}" repo
fi

cd repo

if [ -n "${COMMIT_ID}" ]; then
  git checkout "${COMMIT_ID}"
fi

echo "Checked out commit:"
git rev-parse HEAD
'''
            }
        }
    }

    stage('Install Pixi Environment') {
        container(containerName) {
            runStageWithNotification('Install Pixi Environment') {
                sh '''#!/usr/bin/env bash
set -euo pipefail

cd repo
pixi install --locked || pixi install
'''
            }
        }
    }

    stage('Dry Run') {
        container(containerName) {
            runStageWithNotification('Dry Run') {
                sh '''#!/usr/bin/env bash
set -euo pipefail

cd repo

pixi run snakemake \
  -c 4 \
  --dryrun \
  --snakefile "${SNAKEFILE_PATH}" \
  --configfile "${CONFIG_FILE_PATH}" \
  --printshellcmds
'''
            }
        }
    }

    stage('Run Pipeline') {
        container(containerName) {
            runStageWithNotification('Run Pipeline') {
                sh '''#!/usr/bin/env bash
set -euo pipefail

cd repo

if [ -n "${PIPELINE_RUN_COMMAND}" ]; then
  echo "Running custom pipeline command:"
  echo "${PIPELINE_RUN_COMMAND}"
  eval "${PIPELINE_RUN_COMMAND}"
else
  echo "Running default Snakemake command"

  pixi run snakemake \
    -c 4 \
    --snakefile "${SNAKEFILE_PATH}" \
    --configfile "${CONFIG_FILE_PATH}" \
    --printshellcmds \
    --show-failed-logs \
    --rerun-incomplete
fi
'''
            }
        }
    }

    stage('Run QC') {
        if (env.QC_COMMAND?.trim()) {
            container(containerName) {
                runStageWithNotification('Run QC') {
                    sh '''#!/usr/bin/env bash
set -euo pipefail

cd repo

echo "Running QC command:"
echo "${QC_COMMAND}"

eval "${QC_COMMAND}"
'''
                }
            }
        } else {
            echo "Skipping QC stage because QC_COMMAND is empty."
        }
    }

    stage('Upload outputs to GCS') {
        container(containerName) {
            runStageWithNotification('Upload outputs to GCS') {
                sh """#!/usr/bin/env bash
set -euo pipefail

cd repo

echo "Output directories JSON:"
echo "\${OUTPUT_DIRECTORIES_JSON}"

python - <<'PY' > /tmp/output_dirs.txt
import json
import os

raw = os.environ.get("OUTPUT_DIRECTORIES_JSON", "[]")
dirs = json.loads(raw)

if not isinstance(dirs, list):
    raise ValueError("OUTPUT_DIRECTORIES_JSON must be a JSON list")

for directory in dirs:
    if not isinstance(directory, str):
        raise ValueError("Each output directory must be a string")

    directory = directory.strip()

    if directory:
        print(directory)
PY

if [ ! -s /tmp/output_dirs.txt ]; then
  echo "No output directories were provided in OUTPUT_DIRECTORIES_JSON"
  exit 1
fi

while IFS= read -r output_dir; do
  [ -z "\${output_dir}" ] && continue

  echo "Checking output directory: \${output_dir}"

  if [ ! -d "\${output_dir}" ]; then
    echo "Output directory does not exist: \${output_dir}"
    exit 1
  fi

  echo "Uploading \${output_dir} to ${gcsBucket}/pipelines/\${PIPELINE_NAME}/\${RUN_ID}/\${output_dir}/"

  gcloud storage cp \\
    --recursive \\
    "\${output_dir}" \\
    "${gcsBucket}/pipelines/\${PIPELINE_NAME}/\${RUN_ID}/\${output_dir}/"

done < /tmp/output_dirs.txt

if [ -n "\${QC_OUTPUT_DIRECTORY}" ] && [ -d "\${QC_OUTPUT_DIRECTORY}" ]; then
  echo "Uploading QC output directory: \${QC_OUTPUT_DIRECTORY}"

  gcloud storage cp \\
    --recursive \\
    "\${QC_OUTPUT_DIRECTORY}" \\
    "${gcsBucket}/pipelines/\${PIPELINE_NAME}/\${RUN_ID}/\${QC_OUTPUT_DIRECTORY}/"
fi
"""
            }
        }
    }
}