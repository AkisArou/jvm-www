package io.github.akisarou.jvmwww.web.events;

/** Minimal Web-compatible DOMException value used by the selected mobile profile. */
public class DOMException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final short INDEX_SIZE_ERR = 1;
    public static final short HIERARCHY_REQUEST_ERR = 3;
    public static final short WRONG_DOCUMENT_ERR = 4;
    public static final short INVALID_CHARACTER_ERR = 5;
    public static final short NO_MODIFICATION_ALLOWED_ERR = 7;
    public static final short NOT_FOUND_ERR = 8;
    public static final short NOT_SUPPORTED_ERR = 9;
    public static final short INUSE_ATTRIBUTE_ERR = 10;
    public static final short INVALID_STATE_ERR = 11;
    public static final short SYNTAX_ERR = 12;
    public static final short INVALID_MODIFICATION_ERR = 13;
    public static final short NAMESPACE_ERR = 14;
    public static final short INVALID_ACCESS_ERR = 15;
    public static final short TYPE_MISMATCH_ERR = 17;
    public static final short SECURITY_ERR = 18;
    public static final short NETWORK_ERR = 19;
    public static final short ABORT_ERR = 20;
    public static final short URL_MISMATCH_ERR = 21;
    public static final short QUOTA_EXCEEDED_ERR = 22;
    public static final short TIMEOUT_ERR = 23;
    public static final short INVALID_NODE_TYPE_ERR = 24;
    public static final short DATA_CLONE_ERR = 25;

    private final String name;
    private final short code;

    public DOMException(String message, String name) {
        super(message == null ? "" : message);
        this.name = name == null ? "Error" : name;
        this.code = legacyCode(this.name);
    }

    public String getName() {
        return name;
    }

    public short getCode() {
        return code;
    }

    public static DOMException invalidState(String message) {
        return new DOMException(message, "InvalidStateError");
    }

    public static DOMException abortError() {
        return new DOMException("This operation was aborted", "AbortError");
    }

    public static DOMException timeoutError() {
        return new DOMException("The operation timed out", "TimeoutError");
    }

    private static short legacyCode(String name) {
        if ("IndexSizeError".equals(name)) return INDEX_SIZE_ERR;
        if ("HierarchyRequestError".equals(name)) return HIERARCHY_REQUEST_ERR;
        if ("WrongDocumentError".equals(name)) return WRONG_DOCUMENT_ERR;
        if ("InvalidCharacterError".equals(name)) return INVALID_CHARACTER_ERR;
        if ("NoModificationAllowedError".equals(name)) return NO_MODIFICATION_ALLOWED_ERR;
        if ("NotFoundError".equals(name)) return NOT_FOUND_ERR;
        if ("NotSupportedError".equals(name)) return NOT_SUPPORTED_ERR;
        if ("InUseAttributeError".equals(name)) return INUSE_ATTRIBUTE_ERR;
        if ("InvalidStateError".equals(name)) return INVALID_STATE_ERR;
        if ("SyntaxError".equals(name)) return SYNTAX_ERR;
        if ("InvalidModificationError".equals(name)) return INVALID_MODIFICATION_ERR;
        if ("NamespaceError".equals(name)) return NAMESPACE_ERR;
        if ("InvalidAccessError".equals(name)) return INVALID_ACCESS_ERR;
        if ("TypeMismatchError".equals(name)) return TYPE_MISMATCH_ERR;
        if ("SecurityError".equals(name)) return SECURITY_ERR;
        if ("NetworkError".equals(name)) return NETWORK_ERR;
        if ("AbortError".equals(name)) return ABORT_ERR;
        if ("URLMismatchError".equals(name)) return URL_MISMATCH_ERR;
        if ("QuotaExceededError".equals(name)) return QUOTA_EXCEEDED_ERR;
        if ("TimeoutError".equals(name)) return TIMEOUT_ERR;
        if ("InvalidNodeTypeError".equals(name)) return INVALID_NODE_TYPE_ERR;
        if ("DataCloneError".equals(name)) return DATA_CLONE_ERR;
        return 0;
    }
}
