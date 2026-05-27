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

    String uploadStage = 'Upload outputs to GCS'

    stage(uploadStage) {
        container(containerName) {
            runStageWithNotification(uploadStage) {
                withEnv(["GCS_BUCKET=${gcsBucket}"]) {
                    writeFile(
                        file: 'parse_output_dirs.py',
                        text: '''import json
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

                        if [ -n "${QC_OUTPUT_DIRECTORY}" ] && [ -d "${QC_OUTPUT_DIRECTORY}" ]; then
                            echo "Uploading QC output directory: ${QC_OUTPUT_DIRECTORY}"

                            gcloud storage cp \
                                --recursive \
                                "${QC_OUTPUT_DIRECTORY}" \
                                "${GCS_BUCKET}/pipelines/${PIPELINE_NAME}/${RUN_ID}/"
                        fi
                    '''.stripIndent())
                }
            }
        }
    }
}
