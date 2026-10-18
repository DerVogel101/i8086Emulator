package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.KeyboardInput;
import java.awt.event.KeyEvent;
import java.nio.charset.Charset;
import static java.awt.event.KeyEvent.*;

/** Converts AWT key presses before Swing text composition or focus traversal. */
final class SwingKeyboardInput {
    private static final Charset ENCODING = Charset.forName("windows-1258");
    static int translate(KeyEvent event, KeyboardInput.Layout layout, boolean caps, boolean rightAlt) {
        boolean altGr = event.isAltGraphDown() || rightAlt && layout == KeyboardInput.Layout.DE;
        int key = event.getKeyCode(), scan = -1;
        if (event.getKeyLocation() == KEY_LOCATION_NUMPAD) {
            if (key >= VK_NUMPAD1 && key <= VK_NUMPAD9) scan = 89 + key - VK_NUMPAD1;
            else scan = switch (key) {
                case VK_NUMPAD0, VK_INSERT -> 98; case VK_DECIMAL, VK_SEPARATOR, VK_DELETE -> 99;
                case VK_DIVIDE -> 84; case VK_MULTIPLY -> 85; case VK_SUBTRACT -> 86; case VK_ADD -> 87;
                case VK_ENTER -> 88; case VK_EQUALS -> 103; case VK_END -> 89;
                case VK_DOWN, VK_KP_DOWN -> 90; case VK_PAGE_DOWN -> 91; case VK_LEFT, VK_KP_LEFT -> 92;
                case VK_CLEAR -> 93; case VK_RIGHT, VK_KP_RIGHT -> 94; case VK_HOME -> 95;
                case VK_UP, VK_KP_UP -> 96; case VK_PAGE_UP -> 97; default -> -1;
            };
        } else if (key >= VK_A && key <= VK_Z) {
            if (layout == KeyboardInput.Layout.DE && (key == VK_Y || key == VK_Z)) key = key == VK_Y ? VK_Z : VK_Y;
            scan = 4 + key - VK_A;
        } else if (key >= VK_0 && key <= VK_9) scan = key == VK_0 ? 39 : 30 + key - VK_1;
        else scan = switch (key) {
            case VK_ENTER -> 40; case VK_BACK_SPACE -> 42; case VK_TAB -> 43; case VK_SPACE -> 44;
            case VK_INSERT -> 73; case VK_HOME -> 74; case VK_PAGE_UP -> 75; case VK_DELETE -> 76;
            case VK_END -> 77; case VK_PAGE_DOWN -> 78; case VK_RIGHT -> 79; case VK_LEFT -> 80;
            case VK_DOWN -> 81; case VK_UP -> 82; case VK_COMMA -> 54; case VK_PERIOD -> 55;
            default -> -1;
        };
        if (scan < 0 && layout == KeyboardInput.Layout.US) scan = switch (key) {
            case VK_MINUS -> 45; case VK_EQUALS -> 46; case VK_OPEN_BRACKET -> 47; case VK_CLOSE_BRACKET -> 48;
            case VK_BACK_SLASH -> 49; case VK_SEMICOLON -> 51; case VK_QUOTE -> 52;
            case VK_BACK_QUOTE -> 53; case VK_SLASH -> 56; default -> -1;
        };
        if (scan < 0 && layout == KeyboardInput.Layout.DE) scan = switch (key) {
            case VK_MINUS -> 56; case VK_PLUS -> 48; case VK_NUMBER_SIGN -> 49;
            case VK_LESS, VK_GREATER -> 100; case VK_DEAD_ACUTE, VK_DEAD_GRAVE -> 46;
            case VK_DEAD_CIRCUMFLEX, VK_CIRCUMFLEX -> 53; default -> -1;
        };
        int modifiers = (event.isShiftDown() ? 1 : 0) | (event.isControlDown() ? 0x40 : 0)
                | (event.isAltDown() ? 0x100 : 0) | (event.isMetaDown() ? 0x400 : 0) | (caps ? 0x2000 : 0);
        if (altGr) modifiers |= 0x240;
        if (scan >= 0) return KeyboardInput.translate(layout, scan, modifiers);
        if (event.isMetaDown() || (event.isControlDown() || event.isAltDown()) && !altGr) return -1;
        char value = event.getKeyChar();
        if (value < 32 || value == CHAR_UNDEFINED || !ENCODING.newEncoder().canEncode(value)) return -1;
        int encoded = Byte.toUnsignedInt(Character.toString(value).getBytes(ENCODING)[0]);
        return KeyboardInput.allowedByte(encoded) ? encoded : -1;
    }
}
