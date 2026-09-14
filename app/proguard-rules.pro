# PdfBox-Android — JPEG2000 라이브러리는 넣지 않았다(글만 뽑는다). 글꼴·인코딩 표를
# 이름으로 찾으므로 줄이지 않는다.
-dontwarn com.gemalto.jp2.**
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# OkHttp 이 참조하는 선택적 클래스들 — 없어도 동작한다.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
