package com.bilipai.desktop.danmaku

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Android Matrix's nine row-major homography values, including perspective. */
internal class DesktopBasMatrix {
    internal val values = FloatArray(9)
    init { reset() }
    fun reset() { values.fill(0f); values[0]=1f; values[4]=1f; values[8]=1f }
    fun setValues(source: FloatArray) { require(source.size==9); source.copyInto(values) }
    fun setScale(x: Float,y: Float) { reset(); values[0]=x; values[4]=y }
    // Android postTranslate left-multiplies the existing transform.
    fun postTranslate(x:Float,y:Float) {
        for (column in 0..2) { values[column]+=x*values[6+column]; values[3+column]+=y*values[6+column] }
    }
    fun invert(result:DesktopBasMatrix):Boolean {
        val a=values.map(Float::toDouble)
        val cof=doubleArrayOf(a[4]*a[8]-a[5]*a[7], a[2]*a[7]-a[1]*a[8], a[1]*a[5]-a[2]*a[4],
            a[5]*a[6]-a[3]*a[8], a[0]*a[8]-a[2]*a[6], a[2]*a[3]-a[0]*a[5],
            a[3]*a[7]-a[4]*a[6], a[1]*a[6]-a[0]*a[7], a[0]*a[4]-a[1]*a[3])
        val determinant=a[0]*cof[0]+a[1]*cof[3]+a[2]*cof[6]
        if (!determinant.isFinite() || abs(determinant)<1e-12) return false
        val inverse=FloatArray(9) { (cof[it]/determinant).toFloat() }
        if (!inverse.all(Float::isFinite)) return false
        result.setValues(inverse); return true
    }
    fun mapPoints(points:FloatArray) {
        require(points.size%2==0)
        for (i in points.indices step 2) {
            val x=points[i]; val y=points[i+1]; val w=values[6]*x+values[7]*y+values[8]
            points[i]=(values[0]*x+values[1]*y+values[2])/w
            points[i+1]=(values[3]*x+values[4]*y+values[5])/w
        }
    }
}

/** Column-major OpenGL transforms; operation order matches android.opengl.Matrix. */
internal object DesktopBasMatrix4 {
    fun setIdentityM(matrix:FloatArray,offset:Int) {
        for(i in 0..15)matrix[offset+i]=if(i%5==0)1f else 0f
    }
    fun translateM(matrix:FloatArray,offset:Int,x:Float,y:Float,z:Float) {
        for(row in 0..3)matrix[offset+12+row]+=matrix[offset+row]*x+matrix[offset+4+row]*y+matrix[offset+8+row]*z
    }
    fun scaleM(matrix:FloatArray,offset:Int,x:Float,y:Float,z:Float) {
        for(row in 0..3){matrix[offset+row]*=x;matrix[offset+4+row]*=y;matrix[offset+8+row]*=z}
    }
    fun multiplyMM(result:FloatArray,resultOffset:Int,left:FloatArray,leftOffset:Int,right:FloatArray,rightOffset:Int) {
        val product=FloatArray(16)
        for(column in 0..3)for(row in 0..3) {
            var value=0f
            for(k in 0..3)value+=left[leftOffset+k*4+row]*right[rightOffset+column*4+k]
            product[column*4+row]=value
        }
        product.copyInto(result,resultOffset)
    }
    fun rotateM(matrix:FloatArray,offset:Int,angle:Float,x:Float,y:Float,z:Float) {
        val length=sqrt(x*x+y*y+z*z); require(length>0f && length.isFinite())
        val ax=x/length;val ay=y/length;val az=z/length
        val radians=Math.toRadians(angle.toDouble());val c=cos(radians).toFloat();val s=sin(radians).toFloat();val n=1f-c
        val rotation=floatArrayOf(c+ax*ax*n, ay*ax*n+az*s, az*ax*n-ay*s,0f,
            ax*ay*n-az*s,c+ay*ay*n,az*ay*n+ax*s,0f,
            ax*az*n+ay*s,ay*az*n-ax*s,c+az*az*n,0f,0f,0f,0f,1f)
        multiplyMM(matrix,offset,matrix,offset,rotation,0)
    }
}
