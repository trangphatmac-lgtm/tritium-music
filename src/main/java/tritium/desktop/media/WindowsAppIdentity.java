package tritium.desktop.media;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.ptr.PointerByReference;
import tritium.desktop.TritiumMusicDesktopApp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static tritium.desktop.media.WinRt.*;

/** Gives the Java-hosted player its own Shell identity, including the SMTC source label. */
public final class WindowsAppIdentity {
    static final String APP_ID = "Tritium.Music";
    static final String DISPLAY_NAME = "Tritium Music";
    private static final String PROPERTY_STORE = "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99";
    private static final String PERSIST_FILE = "0000010b-0000-0000-c000-000000000046";

    private WindowsAppIdentity() { }

    /** Must run before creating any UI or SMTC object, including when started from an IDE/browser. */
    public static void initialize() {
        if (!Platform.isWindows()) return;
        try {
            setProcessIdentity(APP_ID);
            Path programs = Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Programs));
            Path shortcut = programs.resolve(DISPLAY_NAME).resolve(DISPLAY_NAME + ".lnk");
            registerShortcut(shortcut, launchSpec(), APP_ID);
        } catch (Exception | LinkageError failure) {
            System.err.println("[SMTC] Windows application identity unavailable: " + failure);
        }
    }

    static void setProcessIdentity(String appId) {
        check(Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(appId)).intValue());
    }

    record LaunchSpec(Path executable, String arguments, Path directory) { }

    static LaunchSpec launchSpec() throws Exception {
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) {
            Path executable = Path.of(packaged).toAbsolutePath();
            return new LaunchSpec(executable, "", executable.getParent());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java.exe");
        Path source = Path.of(TritiumMusicDesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(source)) return jarLaunch(java, source);
        // IDE/class-directory runs need their complete classpath, not a shortcut to java.exe alone.
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        return new LaunchSpec(java, "-cp " + quote(classpath) + " " + TritiumMusicDesktopApp.class.getName(),
                Path.of("").toAbsolutePath());
    }

    static LaunchSpec jarLaunch(Path java, Path jar) {
        Path absolute = jar.toAbsolutePath();
        return new LaunchSpec(java.toAbsolutePath(), "-jar " + quote(absolute.toString()), absolute.getParent());
    }

    /** Windows CommandLineToArgvW/CRT quoting, including trailing backslashes. */
    static String quote(String argument) {
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (char character : argument.toCharArray()) {
            if (character == '\\') { slashes++; continue; }
            result.append("\\".repeat(character == '"' ? slashes * 2 + 1 : slashes));
            result.append(character);
            slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    /** The shortcut is the Shell's display-name registration; no machine-wide registry changes. */
    static void registerShortcut(Path shortcut, LaunchSpec launch, String appId) throws IOException {
        int initialized = Ole32.INSTANCE.CoInitializeEx(null, 2).intValue(); // COINIT_APARTMENTTHREADED
        check(initialized);
        try (ComObject link = newShellLink();
             ComObject properties = link.query(PROPERTY_STORE);
             ComObject file = link.query(PERSIST_FILE)) {
            if (Files.exists(shortcut)) {
                file.call(5, new WString(shortcut.toString()), 2); // IPersistFile.Load, STGM_READWRITE
                if (!appId.equals(readAppId(properties))) {
                    throw new IOException("Will not replace an unrelated shortcut: " + shortcut);
                }
            }
            link.call(20, new WString(launch.executable().toString())); // IShellLinkW.SetPath
            link.call(11, new WString(launch.arguments())); // SetArguments
            link.call(9, new WString(launch.directory().toString())); // SetWorkingDirectory
            link.call(7, new WString(DISPLAY_NAME)); // SetDescription
            link.call(17, new WString(launch.executable().toString()), 0); // SetIconLocation
            try (NativeBuffer key = appIdKey(); NativeBuffer text = new NativeBuffer((appId.length() + 1L) * Native.WCHAR_SIZE);
                 NativeBuffer value = variant()) {
                text.setWideString(0, appId);
                value.setShort(0, (short) 31); // VT_LPWSTR
                value.setPointer(8, text);
                properties.call(6, key, value); // IPropertyStore.SetValue
                properties.call(7); // Commit
            }
            Files.createDirectories(shortcut.getParent());
            file.call(6, new WString(shortcut.toString()), 1); // IPersistFile.Save
        } finally {
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static ComObject newShellLink() {
        PointerByReference result = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(new Guid.GUID("00021401-0000-0000-c000-000000000046"),
                null, 1, new Guid.GUID("000214f9-0000-0000-c000-000000000046"), result).intValue());
        return new ComObject(result.getValue());
    }

    private static NativeBuffer appIdKey() {
        NativeBuffer key = new NativeBuffer(20); // PROPERTYKEY: GUID + DWORD
        Guid.GUID guid = new Guid.GUID("9f4c2855-9f79-4b39-a8d0-e1d42de1d5f3");
        guid.write();
        key.write(0, guid.getPointer().getByteArray(0, 16), 0, 16);
        key.setInt(16, 5); // PKEY_AppUserModel_ID
        return key;
    }

    private static NativeBuffer variant() {
        NativeBuffer value = new NativeBuffer(Native.POINTER_SIZE == 8 ? 24 : 16); // PROPVARIANT
        value.clear();
        return value;
    }

    private static String readAppId(ComObject properties) {
        try (NativeBuffer key = appIdKey(); NativeBuffer value = variant()) {
            properties.call(5, key, value);
            try {
                return value.getShort(0) == 31 ? value.getPointer(8).getWideString(0) : "";
            } finally {
                // GetValue owns its string; use the native PROPVARIANT destructor.
                com.sun.jna.NativeLibrary.getInstance("ole32").getFunction("PropVariantClear", com.sun.jna.Function.ALT_CONVENTION)
                        .invokeInt(new Object[]{value});
            }
        }
    }

    // JNA 5.10 Memory predates AutoCloseable; keep the small Shell ABI buffers scoped.
    private static final class NativeBuffer extends Memory implements AutoCloseable {
        NativeBuffer(long size) { super(size); }
        @Override public void close() { dispose(); }
    }
}
