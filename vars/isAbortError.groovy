def call(err) {
    String className = err.getClass().getName()
    String message = err.toString()

    return (
        className.contains('FlowInterruptedException') ||
        message.contains('FlowInterruptedException') ||
        message.contains('Aborted by') ||
        message.contains('Queue task was cancelled')
    )
}