package com.example.isaworker;

import java.util.function.Function;

/** Lets tests in other packages reach the package-private helpers of {@link IsaWorkerApplication}. */
public final class IsaWorkerApplicationTestAccess {

    private IsaWorkerApplicationTestAccess() {
    }

    public static boolean hasDesktop(String osName, Function<String, String> environment) {
        return IsaWorkerApplication.hasDesktop(osName, environment);
    }
}
