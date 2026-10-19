plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.cooldong.calendar"; compileSdk = 35
 defaultConfig { applicationId = "com.cooldong.calendar"; minSdk = 26; targetSdk = 35; versionCode = 9; versionName = "1.4.3" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}