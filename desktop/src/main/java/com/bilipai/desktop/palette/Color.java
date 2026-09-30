package com.bilipai.desktop.palette;
/** Skia/JVM ARGB channel binding for AndroidX's unmodified quantizer arithmetic. */
final class Color {
 static int red(int c){return (c>>>16)&255;} static int green(int c){return (c>>>8)&255;}
 static int blue(int c){return c&255;} static int rgb(int r,int g,int b){return 0xff000000|(r<<16)|(g<<8)|b;}
}
