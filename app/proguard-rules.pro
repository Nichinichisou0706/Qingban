# PDFBox loads codecs and font implementations reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
# PDFBox's optional JPX codec is not shipped. We extract text with PDFBox and
# render scanned pages with Android PdfRenderer, never PDFBox JPXFilter.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-keep class com.google.mlkit.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
# Keep the prompt's resource anchor available in optimized builds.
-keep class io.github.ming.qingban.agent.AgentCoordinator { *; }
