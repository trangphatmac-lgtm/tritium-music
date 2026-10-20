package tritium.screens.ncm.panels;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.input.Keyboard;
import tritium.rendering.ui.widgets.TextFieldWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;

import static org.junit.jupiter.api.Assertions.*;

class MusicSearchBarTest {
    @ParameterizedTest
    @ValueSource(ints = {Keyboard.KEY_M, Keyboard.KEY_SPACE, Keyboard.KEY_G, Keyboard.KEY_ESCAPE,
            Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER})
    void unfocusedGlobalSearchLeavesKeysForTheCurrentPanel(int keyCode) {
        NCMScreen screen = new NCMScreen() {
            @Override
            public void setCurrentPanel(NCMPanel panel) {
                fail("An unfocused global search must not open results or start a request");
            }
        };
        MusicSearchBar search = new MusicSearchBar(screen);
        TextFieldWidget field = (TextFieldWidget) search.getChildren().stream()
                .filter(TextFieldWidget.class::isInstance).findFirst().orElseThrow();
        field.setText("previous global query");
        assertFalse(field.isFocused());

        assertFalse(search.onKeyTypedReceived('\0', keyCode),
                "Playlist filtering and playback shortcuts must receive the key");
        assertEquals("previous global query", field.getText());
    }
}
