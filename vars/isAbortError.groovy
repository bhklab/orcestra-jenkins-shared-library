def call(Throwable err) {
    String className = err.getClass().getName()
    String message = err

    boolean isAbort = (
        className.contains('FlowInterruptedException') ||
        /* groovylint-disable-next-line DuplicateStringLiteral */
        message.contains('FlowInterruptedException') ||
        message.contains('Aborted by') ||
        message.contains('Queue task was cancelled')
    )

    isAbort
}
