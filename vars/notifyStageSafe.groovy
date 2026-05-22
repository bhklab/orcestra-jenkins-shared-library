def call(Map args = [:]) {
    try {
        notifyStage(args)
    } catch (notifyErr) {
        echo "WARNING: Failed to send stage notification: ${notifyErr}"
    }
}