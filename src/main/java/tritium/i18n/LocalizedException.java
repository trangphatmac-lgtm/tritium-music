package tritium.i18n;

/** Retains a catalog key so an error still follows language changes after the request ends. */
public final class LocalizedException extends IllegalStateException {
    public LocalizedException(String key) {
        super(key);
    }

    @Override
    public String getMessage() {
        return I18n.text(super.getMessage());
    }
}
