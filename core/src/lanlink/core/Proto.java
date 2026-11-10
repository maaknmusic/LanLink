package lanlink.core;

/** Wire constants shared by the Windows and Android apps. */
public final class Proto {
    private Proto() {}

    public static final int VERSION = 1;
    public static final int TCP_PORT = 47801;
    public static final int UDP_PORT = 47800;

    /** Largest message accepted before the PIN check passes. */
    public static final int MAX_PREAUTH = 1024;
    /** Largest message accepted from an authenticated peer. */
    public static final int MAX_MESSAGE = 16 * 1024 * 1024;
    public static final int FILE_CHUNK_SIZE = 64 * 1024;
    public static final int MAX_CLIPBOARD = 1024 * 1024;

    // Message types
    public static final byte HELLO = 1, CHALLENGE = 2, AUTH = 3, AUTH_RESULT = 4;
    public static final byte FRAME = 10, FRAME_ACK = 11;
    public static final byte POINTER = 20, SCROLL = 21, KEY = 22, TEXT = 23, ACTION = 24;
    public static final byte CLIPBOARD = 30;
    public static final byte FILE_START = 40, FILE_CHUNK = 41, FILE_END = 42;

    // POINTER actions and buttons
    public static final int DOWN = 0, MOVE = 1, UP = 2;
    public static final int BTN_LEFT = 0, BTN_RIGHT = 1, BTN_MIDDLE = 2;

    // ACTION codes (system buttons, meaningful on Android hosts)
    public static final int ACT_BACK = 1, ACT_HOME = 2, ACT_RECENTS = 3, ACT_NOTIFICATIONS = 4;

    public static final String WINDOWS = "windows", ANDROID = "android";
}
