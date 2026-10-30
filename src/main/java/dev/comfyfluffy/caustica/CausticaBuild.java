package dev.comfyfluffy.caustica;

/**
 * Release vs. development build. The release packaging adds {@code /caustica-release.marker} to the jar;
 * a release hides the developer tools (diagnostics page, performance survey, showcase filming) and keeps
 * the diagnostic settings off whatever an old config file says.
 */
public final class CausticaBuild {
    public static final boolean RELEASE = CausticaBuild.class.getResource("/caustica-release.marker") != null;

    private CausticaBuild() {}
}
