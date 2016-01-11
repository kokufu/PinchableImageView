PinchableImageView
==================

This is a simple ImageView with pinch in/out function for Android.

## How to use
First, import the library. If you use Android Studio (Gradle), you can do it like below.

build.gradle
```gradle
repositories {
    maven { url 'http://kokufu.github.io/maven-repo' }
}

dependencies {
    compile fileTree(dir: 'libs', include: ['*.jar'])
    compile 'com.kokufu.android.lib.ui.widget:pinchableimageview-aar:1.1'
}
```

Then, You can use it as same as [android.widget.ImageView](http://developer.android.com/reference/android/widget/ImageView.html).

layout/main.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.kokufu.android.lib.ui.widget.PinchableImageView
        android:id="@+id/imageView1"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="center"
        tools:ignore="ContentDescription" />

</FrameLayout>
```
