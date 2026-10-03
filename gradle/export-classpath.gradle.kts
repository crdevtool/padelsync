import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute

// Copies every external library the debug build compiles against into
// build/compile-classpath, one jar per library, named after its Maven
// coordinates. Libraries shipped as .aar are unpacked to their classes jar.
//
// This exists so the code can be type-checked on a machine that cannot reach
// Maven: run it once where the network is open, carry the jars across.
tasks.register("exportCompileClasspath") {
    description = "Copies the external libraries the app compiles against into build/compile-classpath."
    group = "help"
    val output = layout.buildDirectory.dir("compile-classpath")
    doLast {
        val artifacts = project.configurations.getByName("debugCompileClasspath").incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            componentFilter { it is ModuleComponentIdentifier }
        }.artifacts
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        for (artifact in artifacts.artifacts) {
            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
            artifact.file.copyTo(File(directory, "${id.group}.${id.module}-${id.version}.jar"), overwrite = true)
        }
        println("Exported ${directory.listFiles()?.size ?: 0} libraries to $directory")
    }
}
