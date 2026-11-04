package local.nahw.hwpxstudio;

final class EngineException extends Exception {
    private static final long serialVersionUID = 1L;

    EngineException(String message) {
        super(message);
    }

    EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
