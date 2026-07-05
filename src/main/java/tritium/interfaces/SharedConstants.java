package tritium.interfaces;

import tritium.desktop.DesktopApi;
import tritium.desktop.DesktopAppState;

/**
 * Commonly shared constants between the classes.
 *
 * @author IzumiiKonata
 * @since 11/19/2023
 */
public interface SharedConstants {

    DesktopApi api = DesktopAppState.api();

}
