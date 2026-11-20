import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("daybook")
        browser {
            commonWebpackConfig {
                outputFileName = "daybook.js"
            }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.addAll("androidx.compose.ui.ExperimentalComposeUiApi", "kotlin.js.ExperimentalWasmJsInterop")
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.ktor.client.js)
        }
    }
}

/**
 * The website: the optimised WebAssembly, its ES-module loaders, Skia's runtime, fonts, icons and the
 * page. No webpack: the browser loads the modules directly, with an import map for the one npm package.
 * Output: build/dist/web, which the sync server serves.
 */
tasks.register<Sync>("webDist") {
    group = "distribution"
    description = "Assembles the Daybook website into build/dist/web"
    from(tasks.named("compileProductionExecutableKotlinWasmJsOptimize")) {
        include("*.mjs", "*.wasm")
    }
    from(tasks.named("processSkikoRuntimeForKWasm")) {
        include("skiko.mjs", "skiko.wasm")
    }
    from(tasks.named("wasmJsProcessResources"))
    into(layout.buildDirectory.dir("dist/web"))
}
