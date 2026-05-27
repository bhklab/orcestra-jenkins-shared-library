void call(String label, String scriptBody, int tailLines = 80) {
    String safeLabel = label
        .replaceAll('[^A-Za-z0-9_.-]', '-')
        .toLowerCase()

    String scriptFile = ".jenkins-${safeLabel}.sh"
    String logFile = ".jenkins-${safeLabel}.log"

    writeFile(
        file: scriptFile,
        text: """#!/usr/bin/env bash
                set -euo pipefail

                ${scriptBody}
            """.stripIndent()
    )

    int status = sh(
        script: """#!/usr/bin/env bash
                set +e

                bash "${scriptFile}" 2>&1 | tee "${logFile}"
                status=\${PIPESTATUS[0]}

                exit \${status}
            """.stripIndent(),
        returnStatus: true
    )

    if (status != 0) {
        String output = ''

        if (fileExists(logFile)) {
            output = sh(
                script: "tail -n ${tailLines} '${logFile}'",
                returnStdout: true
            ).trim()
        }

        output = output ?: "Command failed with exit code $status, but no output was captured."

        error("${label} failed with exit code ${status} || ${output}")
    }
}
