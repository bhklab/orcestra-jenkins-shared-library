import groovy.json.JsonOutput

def call(Map args = [:]) {
    String stageName = args.stage ?: 'Unknown'
    String status = args.status ?: 'unknown'
    String message = args.get('message', '')

    String payload = JsonOutput.toJson([
        run_id           : env.RUN_ID,
        pipeline_name    : env.PIPELINE_NAME,
        stage            : stageName,
        status           : status,
        message          : message,
        jenkins_build_url: env.BUILD_URL,
        email            : env.EMAIL
    ])

    writeFile file: 'stage-event.json', text: payload

    withCredentials([
        string(credentialsId: 'orcestra-api-token', variable: 'API_TOKEN'),
        string(credentialsId: 'orcestra-api-url', variable: 'ORCESTRA_API_URL')
    ]) {
        sh '''#!/usr/bin/env bash
set -euo pipefail

curl --fail-with-body -sS -X POST "${ORCESTRA_API_URL}" \
  -H "Content-Type: application/json" \
  -H "X-Jenkins-Token: ${API_TOKEN}" \
  --data @stage-event.json
'''
    }
}