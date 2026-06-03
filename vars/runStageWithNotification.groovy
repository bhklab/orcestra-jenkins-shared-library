void call(String stageName, Closure body) {
    env.CURRENT_PIPELINE_STAGE = stageName

    notifyStageSafe(stage: stageName, status: 'running')

    try {
        body.call()
        env.CURRENT_PIPELINE_STAGE = ''
    } catch (err) {
        if (isAbortError(err)) {
            echo "Stage '${stageName}' was aborted; skipping failed stage notification."
        } else {
            notifyStageSafe(stage: stageName, status: 'failed', message: "${err}")
            env.CURRENT_PIPELINE_STAGE = ''
        }

        throw err
    }
}
