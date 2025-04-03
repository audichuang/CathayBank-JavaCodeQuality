plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.4.0"
    id("io.freefair.lombok") version "8.4"
}

group = "com.cathaybk"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform.defaultRepositories()
}

// 強制使用特定版本的依賴項，解決衝突
configurations.all {
    resolutionStrategy {
        // 強制使用指定版本的 Jackson
        force("com.fasterxml.jackson.core:jackson-core:2.15.2")
        force("com.fasterxml.jackson.core:jackson-databind:2.15.2")
        force("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
        
        // 緩存策略
        cacheChangingModulesFor(0, "seconds")
        cacheDynamicVersionsFor(0, "seconds")
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    
    // 添加 lombok 依賴
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    
    // 添加 Jackson 依賴（明確指定版本）
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    
    // 指定 IntelliJ Platform 依賴
    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("com.intellij.java")
    }
}

// 設置源碼目錄
sourceSets {
    main {
        java {
            srcDirs("src/main/java")
        }
        resources {
            srcDirs("src/main/resources")
        }
    }
}

tasks {
    // 配置 Java 版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    
    test {
        useJUnitPlatform()
    }
    
    // 解決重複文件的問題
    withType<Copy> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    prepareSandbox {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    // 跳過 buildSearchableOptions 任務
    named("buildSearchableOptions") {
        enabled = false
    }
    
    // 設置執行 IDE 的選項
    named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
        // 設置系統屬性
        systemProperty("idea.platform.prefix", "idea")
        // 設置臨時目錄在較短的路徑
        systemProperty("java.io.tmpdir", "${System.getProperty("user.home")}/temp")
        // 分配更多記憶體
        jvmArgs("-Xmx1g")
        
        // 如果需要，複製原有的plugin.xml到目標位置
        doFirst {
            val originPluginXml = file("src/main/resources/META-INF/plugin.xml")
            val targetDir = file("${buildDir}/tmp/patchPluginXml")
            if (originPluginXml.exists() && !targetDir.exists()) {
                targetDir.mkdirs()
                originPluginXml.copyTo(file("${targetDir}/plugin.xml"), overwrite = true)
                println("已複製plugin.xml到${targetDir}/plugin.xml")
            }
        }
    }
    
    // 為所有 JavaExec 類型的任務設置系統屬性
    withType<JavaExec> {
        systemProperty("idea.platform.prefix", "idea")
        systemProperty("java.io.tmpdir", "${System.getProperty("user.home")}/temp")
        jvmArgs("-Xmx1g")
    }
    
    // 創建一個自定義構建任務，跳過 buildSearchableOptions
    register("customBuild") {
        group = "build"
        description = "跳過 buildSearchableOptions 任務的自定義構建"
        
        // 依賴 build 任務但跳過 buildSearchableOptions
        dependsOn(
            "classes", 
            "testClasses", 
            "jar", 
            "verifyPlugin", 
            "prepareSandbox"
        )
    }
    
    // 添加強化版清理緩存的任務
    register("cleanCache") {
        doLast {
            // 清理 Gradle 緩存
            val gradleHome = File(System.getProperty("user.home") + "/.gradle")
            if (gradleHome.exists()) {
                // 刪除特定問題的 JAR 文件
                val problemJar = File(System.getProperty("user.home") + 
                    "/.gradle/caches/jars-9/21f503c55fdde83d51a4f784631b641e/jackson-core-2.17.2.jar")
                if (problemJar.exists()) {
                    delete(problemJar)
                    println("已刪除問題 JAR 文件: ${problemJar.absolutePath}")
                }
                
                // 刪除 modules-2 子目錄
                val modules2Dir = File(gradleHome, "caches/modules-2")
                if (modules2Dir.exists()) {
                    delete(modules2Dir)
                    println("已刪除 modules-2 緩存目錄")
                }
                
                // 刪除 jars-9 子目錄
                val jarsDir = File(gradleHome, "caches/jars-9")
                if (jarsDir.exists()) {
                    delete(jarsDir)
                    println("已刪除 jars-9 緩存目錄")
                }
            }
            
            // 確保臨時目錄存在
            val tempDir = File("${System.getProperty("user.home")}/temp")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
                println("已創建臨時目錄: ${tempDir.absolutePath}")
            }
            
            println("緩存清理完成")
        }
    }
}

// 配置 IntelliJ Platform 插件
intellijPlatform {
    pluginConfiguration {
        id.set("com.cathaybk.codingassistant")
        name.set("CathayBk Coding Assistant")
        version.set(project.version.toString())
        
        vendor {
            name.set("CathayBk")
            email.set("support@cathaybk.com")
            url.set("https://www.cathaybk.com")
        }
        
        description.set("""
            國泰銀行程式碼助手，用於協助開發人員遵循公司的編碼標準和規範。
            主要功能：
            - 檢查Controller層API方法是否添加了msgID註解
            - 自動關聯Controller的API方法與對應的Service實現
            - 提供程式碼規範檢查並顯示警告
        """)
        
        ideaVersion {
            sinceBuild.set("231")
            untilBuild.set("243.*")
        }
        
        changeNotes.set("""
            <ul>
                <li>1.0.0: 初始版本，提供API註解檢查和Service關聯功能</li>
            </ul>
        """)
    }
}

// 更改緩存目錄以避免路徑過長問題
gradle.projectsEvaluated {
    val tempDir = File("${System.getProperty("user.home")}/cathaybk-gradle-temp")
    if (!tempDir.exists()) {
        tempDir.mkdirs()
    }
    System.setProperty("gradle.user.home", tempDir.absolutePath)
} 