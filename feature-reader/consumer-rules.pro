# SubsamplingScaleImageView creates its decoders through CompatDecoderFactory,
# which looks up constructors by reflection. R8 must keep them.
-keepclassmembers class com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder {
    public <init>(...);
}
-keepclassmembers class com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder {
    public <init>(...);
}
-keepclassmembers class com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder {
    public <init>(...);
}
