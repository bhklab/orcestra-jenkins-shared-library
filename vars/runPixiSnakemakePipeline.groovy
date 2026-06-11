def call(Map args = [:]) {
    String containerName = args.get('containerName', 'pixi')
    String gcsBucket = args.get('gcsBucket', 'gs://nicholas-testing')
    String checkoutStage = 'Checkout'
    stage(checkoutStage) {
        container(containerName) {
            runStageWithNotification(checkoutStage) {
                runShellWithCapturedError(checkoutStage, '''

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
                '''.stripIndent())
            }
        }
    }

    String verifyFilesStage = 'Verify Snakemake and config files'

    stage(verifyFilesStage) {
        container(containerName) {
            runStageWithNotification(verifyFilesStage) {
                runShellWithCapturedError(verifyFilesStage, '''

                    cd repo
                    if [ ! -f "${SNAKEFILE_PATH}" ]; then
                    echo "Error: Snakefile not found at ${SNAKEFILE_PATH}"
                    exit 1
                    fi

                    if [ ! -f "${CONFIG_FILE_PATH}" ]; then
                    echo "Error: Config file not found at ${CONFIG_FILE_PATH}"
                    exit 1
                    fi

                    echo "Found Snakefile at ${SNAKEFILE_PATH}"
                    echo "Found config file at ${CONFIG_FILE_PATH}"
                '''.stripIndent())
            }
        }
    }

    String installStage = 'Install Pixi Environment'

    stage(installStage) {
        container(containerName) {
            runStageWithNotification(installStage) {
                runShellWithCapturedError(installStage, '''

                    cd repo
                    pixi install --locked || pixi install
                '''.stripIndent())
            }
        }
    }

    String dryRunStage = 'Dry Run'

    stage(dryRunStage) {
        container(containerName) {
            runStageWithNotification(dryRunStage) {
                runShellWithCapturedError(dryRunStage, '''
                    cd repo

                    pixi run snakemake \
                    -c 4 \
                    --dryrun \
                    --snakefile "${SNAKEFILE_PATH}" \
                    --configfile "${CONFIG_FILE_PATH}" \
                    --printshellcmds
                '''.stripIndent())
            }
        }
    }

    String runPipelineStage = 'Run Pipeline'

    stage(runPipelineStage) {
        container(containerName) {
            runStageWithNotification(runPipelineStage) {
                runShellWithCapturedError(runPipelineStage, '''
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
                '''.stripIndent())
            }
        }
    }

    String qcStage = 'Run QC'

    stage(qcStage) {
        if (env.QC_COMMAND?.trim()) {
            container(containerName) {
                runStageWithNotification(qcStage) {
                    runShellWithCapturedError(qcStage, '''

                        cd repo

                        echo "Running QC command:"
                        echo "${QC_COMMAND}"

                        eval "${QC_COMMAND}"
                    '''.stripIndent())
                }
            }
        } else {
            echo 'Skipping QC stage because QC_COMMAND is empty.'
        }
    }

    String checksumStage = 'Generate output checksums'

    stage(checksumStage) {
        container(containerName) {
            runStageWithNotification(checksumStage) {
                withEnv(["GCS_BUCKET=${gcsBucket}"]) {
                    writeFile(
                        file: 'build_checksum_manifest.py',
                        text: '''import json
import os
import hashlib
from pathlib import Path
from datetime import datetime, timezone

def calculate_checksums(path: Path, chunk_size: int = 1024 * 1024) -> dict:
    sha256 = hashlib.sha256()
    md5 = hashlib.md5()

    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(chunk_size), b""):
            sha256.update(chunk)
            md5.update(chunk)

    return {
        "sha256": sha256.hexdigest(),
        "md5": md5.hexdigest(),
        "size_bytes": path.stat().st_size,
    }

def collect_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]

    if path.is_dir():
        return sorted([p for p in path.rglob("*") if p.is_file()])

    raise FileNotFoundError(f"Output path does not exist: {path}")

raw = os.environ.get("OUTPUT_DIRECTORIES_JSON", "[]")
output_dirs = json.loads(raw)

if not isinstance(output_dirs, list):
    raise ValueError("OUTPUT_DIRECTORIES_JSON must be a JSON list")

repo_root = Path("repo").resolve()

pipeline_name = os.environ["PIPELINE_NAME"]
run_id = os.environ["RUN_ID"]
gcs_bucket = os.environ["GCS_BUCKET"].rstrip("/")
gcs_run_prefix = f"{gcs_bucket}/pipelines/{pipeline_name}/{run_id}"

manifest = {
    "pipeline_name": pipeline_name,
    "run_id": run_id,
    "generated_at": datetime.now(timezone.utc).isoformat(),
    "checksum_algorithm": "sha256",
    "secondary_checksum_algorithm": "md5",
    "gcs_run_prefix": gcs_run_prefix,
    "files": [],
}

seen = set()

for output_dir in output_dirs:
    if not isinstance(output_dir, str):
        raise ValueError("Each output directory must be a string")

    output_dir = output_dir.strip()

    if not output_dir:
        continue

    output_path = repo_root / output_dir

    if not output_path.exists():
        raise FileNotFoundError(f"Output path does not exist: {output_dir}")

    for file_path in collect_files(output_path):
        relative_path = file_path.relative_to(repo_root).as_posix()

        if relative_path in seen:
            continue

        seen.add(relative_path)

        checksums = calculate_checksums(file_path)

        manifest["files"].append({
            "relative_path": relative_path,
            "filename": file_path.name,
            "size_bytes": checksums["size_bytes"],
            "sha256": checksums["sha256"],
            "md5": checksums["md5"],
            "gcs_uri": f"{gcs_run_prefix}/{relative_path}",
        })

manifest["file_count"] = len(manifest["files"])

Path("checksum_manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\\n",
    encoding="utf-8",
)

print(f"Wrote checksum_manifest.json with {manifest['file_count']} files")
'''
                    )

                    runShellWithCapturedError(checksumStage, '''
                        echo "Generating checksum manifest"

                        if command -v python3 >/dev/null 2>&1; then
                            python3 build_checksum_manifest.py
                        else
                            cd repo
                            pixi run python ../build_checksum_manifest.py
                            cd ..
                        fi

                        echo "Checksum manifest:"
                        cat checksum_manifest.json
                    '''.stripIndent())

                    archiveArtifacts artifacts: 'checksum_manifest.json', fingerprint: true
                }
            }
        }
    }

    String uploadStage = 'Upload outputs to GCS'

    stage(uploadStage) {
        container(containerName) {
            runStageWithNotification(uploadStage) {
                withEnv(["GCS_BUCKET=${gcsBucket}"]) {
                    writeFile(
                        file: 'parse_output_dirs.py',
                        text: '''import json

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
'''
                    )

                    runShellWithCapturedError(uploadStage, '''
                        cd repo

                        echo "Output directories JSON:"
                        echo "${OUTPUT_DIRECTORIES_JSON}"

                        python3 ../parse_output_dirs.py > /tmp/output_dirs.txt

                        if [ ! -s /tmp/output_dirs.txt ]; then
                            echo "No output directories were provided in OUTPUT_DIRECTORIES_JSON"
                            exit 1
                        fi

                        while IFS= read -r output_dir; do
                            [ -z "${output_dir}" ] && continue

                            echo "Checking output directory: ${output_dir}"

                            if [ ! -d "${output_dir}" ]; then
                                echo "Output directory does not exist: ${output_dir}"
                                exit 1
                            fi

                            echo "Uploading ${output_dir} to ${GCS_BUCKET}/pipelines/${PIPELINE_NAME}/${RUN_ID}/${output_dir}/"

                            gcloud storage cp \
                                --recursive \
                                "${output_dir}" \
                                "${GCS_BUCKET}/pipelines/${PIPELINE_NAME}/${RUN_ID}/"

                        done < /tmp/output_dirs.txt

                        echo "Uploading checksum manifest"

                        gcloud storage cp \
                            "../checksum_manifest.json" \
                            "${GCS_BUCKET}/pipelines/${PIPELINE_NAME}/${RUN_ID}/checksum_manifest.json"
                    '''.stripIndent())
                }
            }
        }
    }
}
