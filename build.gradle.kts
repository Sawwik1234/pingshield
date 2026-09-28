plugins {
    java
}

group = "com.sawwik.pingshield"
// Версия по умолчанию; можно переопределить при сборке: ./gradlew build -Pversion=1.7.0
version = providers.gradleProperty("version").orElse("1.6.5").get()
description = "Защита игроков с очень высоким пингом: заморозка движения и иммунитет к урону. Folia-ready."

// ---------------------------------------------------------------------------
//  Репозитории: paper-api лежит в репозитории PaperMC.
//  Компилируемся против paper-api (а не folia-api) — такой JAR работает и на
//  Paper/Purpur, и на Folia: GlobalRegion/Region/Async/Entity планировщики
//  есть и в paper-api, а в plugin.yml стоит folia-supported: true.
// ---------------------------------------------------------------------------
repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
        // Репозиторий PaperMC не отдаёт Gradle Module Metadata (.module -> HTTP 502),
        // поэтому берём зависимости по POM, а иначе Gradle падает с ошибкой.
        metadataSources {
            mavenPom()
            artifact()
        }
    }
    maven {
        // Официальный репозиторий CoreProtect (документация: maven.playpro.com)
        name = "playpro"
        url = uri("https://maven.playpro.com")
    }
}

dependencies {
    // 26.1.2.build.74-stable — последний стабильный билд линии 26.1.2 (та же, что и ваш сервер).
    // Хотите "последний в линии": "26.1.2.build.+"
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // CoreProtect API (soft-depend): нужен только для компиляции.
    // Если на сервере CoreProtect не установлен — интеграция просто выключится.
    compileOnly("net.coreprotect:coreprotect:24.1")

    // LuckPerms API 5.x (soft-depend): персональные пороги и режимы заморозки
    // через права/meta LuckPerms. Без плагина на сервере всё работает на правах Bukkit.
    compileOnly("net.luckperms:api:5.5")

    // --- тесты ---
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Тестам нужен paper-api в runtime (Location, YamlConfiguration, Component);
    // в основной сборке он остаётся compileOnly и в JAR не попадает.
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
}

java {
    // Minecraft/Paper 26.x требует Java 25 (как для компиляции, так и для запуска сервера).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// В src/test лежат и JUnit-тесты, и симулятор (задача simulate, без @Test) —
// discovery-ошибку на «лишних» классах гасим.
tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    // В plugin.yml есть ${version} — подставляем версию из build.gradle.kts
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName = "PingShield-${project.version}.jar"
}

// Симулятор поведения на плохих каналах: ./gradlew simulate
// Показывает, сколько раз плагин заморозит игрока при скачущем пинге —
// в сравнении с наивной реализацией «пинг выше порога → морозим».
tasks.register<JavaExec>("simulate") {
    group = "pingshield"
    description = "Прогоняет симуляцию решений на плохих/нормальных каналах связи"
    mainClass = "com.sawwik.pingshield.PingSimulation"
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

// Нагрузочный стенд: ./gradlew load  (или -Pplayers=1500 -Pcycles=120)
// Показывает, сколько нс/мкс стоит горячий путь на большом онлайне.
tasks.register<JavaExec>("load") {
    group = "pingshield"
    description = "Нагрузочный стенд: горячий путь цикла на 600+ игроков (TOC в тик)"
    mainClass = "com.sawwik.pingshield.PingShieldLoadTest"
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    (findProperty("players") as String?)?.let { systemProperty("players", it) }
    (findProperty("cycles") as String?)?.let { systemProperty("cycles", it) }
}

// Удобный алиас: ./gradlew deploy — собрать и скопировать JAR в папку сервера.
// Путь задаётся в gradle.properties (pluginsDir) или через -PpluginsDir=...
tasks.register<Copy>("deploy") {
    group = "pingshield"
    description = "Собирает плагин и копирует JAR в папку plugins вашего сервера"
    dependsOn(tasks.jar)
    val target = (findProperty("pluginsDir") as String?) ?: "${rootDir}/server/plugins"
    from(tasks.jar.flatMap { it.archiveFile })
    into(target)
    doLast {
        logger.lifecycle("PingShield: JAR скопирован в $target")
    }
}
