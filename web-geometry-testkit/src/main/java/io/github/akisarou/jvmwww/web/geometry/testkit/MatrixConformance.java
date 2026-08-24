package io.github.akisarou.jvmwww.web.geometry.testkit;

import io.github.akisarou.jvmwww.web.geometry.DOMMatrix;
import io.github.akisarou.jvmwww.web.geometry.DOMMatrixReadOnly;
import io.github.akisarou.jvmwww.web.geometry.DOMPoint;
import java.util.Random;

public final class MatrixConformance {
    private int passed;
    public static void main(String[] args) { new MatrixConformance().run(); }
    private void run() {
        constructorsAndDimensionality();
        stickyDimensionality();
        transformPoint();
        twoDimensionalTransforms();
        multiplyAndPreMultiply();
        rotationsAndSkews();
        inversion();
        singularInversion();
        aliasing();
        randomizedProductsAndInverses();
        System.out.println("DOMMatrix conformance: " + passed + " tests passed");
    }

    private void constructorsAndDimensionality() {
        DOMMatrixReadOnly i = new DOMMatrixReadOnly();
        yes(i.is2D(), "identity 2D"); yes(i.isIdentity(), "identity");
        DOMMatrixReadOnly a = new DOMMatrixReadOnly(2,3,4,5,6,7);
        eq(2,a.getA(),"a"); eq(3,a.getB(),"b"); eq(4,a.getC(),"c"); eq(5,a.getD(),"d"); eq(6,a.getE(),"e"); eq(7,a.getF(),"f"); yes(a.is2D(),"six 2D");
        DOMMatrixReadOnly b = new DOMMatrixReadOnly(new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
        no(b.is2D(), "16 values always 3D"); yes(b.isIdentity(), "16 identity math");
        DOMMatrixReadOnly c = new DOMMatrixReadOnly(b); no(c.is2D(), "copy flag");
        badLength(new double[0]); badLength(new double[5]); badLength(new double[15]); badLength(new double[17]);
        pass();
    }

    private void stickyDimensionality() {
        DOMMatrix m = new DOMMatrix();
        m.setM13(-0.0); yes(m.is2D(), "-0 preserves 2D");
        m.setM33(1.0); yes(m.is2D(), "one preserves 2D");
        m.setM13(2.0); no(m.is2D(), "3D write clears");
        m.setM13(0.0); no(m.is2D(), "sticky false");
        DOMMatrix originZ = new DOMMatrix().scaleSelf(1,1,1,0,0,1);
        no(originZ.is2D(), "3D origin clears even identity result");
        pass();
    }

    private void transformPoint() {
        DOMMatrixReadOnly m = new DOMMatrixReadOnly(2,0,0,3,10,20);
        DOMPoint p = m.transformPoint(new DOMPoint(4,5,6,1));
        eq(18,p.getX(),"point x"); eq(35,p.getY(),"point y"); eq(6,p.getZ(),"point z"); eq(1,p.getW(),"point w");
        DOMPoint p2 = new DOMPoint(4,5,6,1).matrixTransform(m);
        eq(18,p2.getX(),"matrixTransform x");
        DOMPoint copy = new DOMPoint(1,2,3,4).matrixTransform(null);
        eq(1,copy.getX(),"null matrix identity"); eq(4,copy.getW(),"null matrix w");
        pass();
    }

    private void twoDimensionalTransforms() {
        DOMMatrix m = new DOMMatrix(1,2,3,4,5,6);
        m.translateSelf(7,8);
        eq(36,m.getE(),"translate e"); eq(52,m.getF(),"translate f");
        m = new DOMMatrix(1,2,3,4,5,6).scaleSelf(2,3);
        eq(2,m.getA(),"scale a"); eq(4,m.getB(),"scale b"); eq(9,m.getC(),"scale c"); eq(12,m.getD(),"scale d"); eq(5,m.getE(),"scale e");
        m = new DOMMatrix().translateSelf(10,20).scaleSelf(2,3,1,4,5,0);
        DOMPoint q = m.transformPoint(new DOMPoint(4,5));
        eq(14,q.getX(),"origin scale x"); eq(25,q.getY(),"origin scale y");
        pass();
    }

    private void multiplyAndPreMultiply() {
        DOMMatrix a = new DOMMatrix(1,2,3,4,5,6);
        DOMMatrix b = new DOMMatrix(7,8,9,10,11,12);
        DOMMatrix p = new DOMMatrix(a).multiplySelf(b);
        double[] ref = mul(values(a), values(b));
        matrix(ref,p,"multiply");
        DOMMatrix q = new DOMMatrix(a).preMultiplySelf(b);
        matrix(mul(values(b),values(a)),q,"premultiply");
        pass();
    }

    private void rotationsAndSkews() {
        DOMPoint x = new DOMMatrix().rotateSelf(90).transformPoint(new DOMPoint(1,0));
        near(0,x.getX(),1e-12,"rotate x"); near(1,x.getY(),1e-12,"rotate y");
        DOMMatrix r3 = new DOMMatrix().rotateSelf(90,90,90); no(r3.is2D(),"3d rotate flag");
        DOMMatrix folded = new DOMMatrix().rotateSelf(180,180,90);
        near(0, folded.getA(), 1e-12, "folded rotation a");
        near(-1, folded.getB(), 1e-12, "folded rotation b");
        near(1, folded.getC(), 1e-12, "folded rotation c");
        near(0, folded.getD(), 1e-12, "folded rotation d");
        no(folded.is2D(), "folded rotation stays observably 3D");
        DOMMatrix axis = new DOMMatrix().rotateAxisAngleSelf(0,0,3,90);
        DOMPoint y = axis.transformPoint(new DOMPoint(1,0)); near(0,y.getX(),1e-12,"axis x"); near(1,y.getY(),1e-12,"axis y"); yes(axis.is2D(),"z axis 2d");
        DOMMatrix sx = new DOMMatrix().skewXSelf(45); near(1,sx.getC(),1e-12,"skew x");
        DOMMatrix sy = new DOMMatrix().skewYSelf(45); near(1,sy.getB(),1e-12,"skew y");
        pass();
    }

    private void inversion() {
        DOMMatrix m = new DOMMatrix(2,1,3,4,5,6);
        DOMMatrix inv = new DOMMatrix(m).invertSelf(); yes(inv.is2D(),"2d inverse remains 2d");
        matrixIdentity(m.multiply(inv),1e-12,"2d inverse product");
        DOMMatrix m3 = new DOMMatrix(new double[]{2,1,0,0,1,3,0,0,0,0,4,0,5,6,7,1});
        DOMMatrix inv3 = new DOMMatrix(m3).invertSelf(); no(inv3.is2D(),"3d inverse flag");
        matrixIdentity(m3.multiply(inv3),1e-11,"4d inverse product");
        pass();
    }

    private void singularInversion() {
        DOMMatrix m = new DOMMatrix(); m.setA(0); m.invertSelf();
        no(m.is2D(),"singular clears 2D"); no(m.isIdentity(),"singular not identity");
        for (double v : values(m)) yes(Double.isNaN(v),"singular NaN");
        pass();
    }

    private void aliasing() {
        DOMMatrix m = new DOMMatrix(1,2,3,4,5,6);
        double[] expected = mul(values(m), values(m));
        m.multiplySelf(m); matrix(expected,m,"multiply alias");
        DOMMatrix n = new DOMMatrix(1,2,3,4,5,6);
        expected = mul(values(n), values(n));
        n.preMultiplySelf(n); matrix(expected,n,"premultiply alias");
        pass();
    }

    private void randomizedProductsAndInverses() {
        Random random = new Random(0x5eed1234L);
        for (int i=0;i<10000;i++) {
            double[] av = randomMatrix(random), bv = randomMatrix(random);
            DOMMatrix a = matrix(av), b = matrix(bv);
            matrix(mul(av,bv),new DOMMatrix(a).multiplySelf(b),"random mul "+i);
            matrix(mul(bv,av),new DOMMatrix(a).preMultiplySelf(b),"random pre "+i);
            DOMMatrix inv = new DOMMatrix(a).invertSelf();
            if (!Double.isNaN(inv.getM11())) matrixIdentity(a.multiply(inv),2e-8,"random inverse "+i);
        }
        pass();
    }

    private static void badLength(double[] values) {
        try { new DOMMatrixReadOnly(values); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid sequence length accepted: " + values.length);
    }
    private static double[] randomMatrix(Random r) {
        double[] v = new double[16];
        for (int i=0;i<16;i++) v[i]=(r.nextDouble()*4.0)-2.0;
        v[0]+=4;v[5]+=4;v[10]+=4;v[15]+=4;
        return v;
    }
    private static DOMMatrix matrix(double[] v){return new DOMMatrix(v);}
    private static double[] values(DOMMatrixReadOnly m){return new double[]{m.getM11(),m.getM12(),m.getM13(),m.getM14(),m.getM21(),m.getM22(),m.getM23(),m.getM24(),m.getM31(),m.getM32(),m.getM33(),m.getM34(),m.getM41(),m.getM42(),m.getM43(),m.getM44()};}
    private static double[] mul(double[] a,double[] b){double[] c=new double[16];for(int col=0;col<4;col++)for(int row=0;row<4;row++){double s=0;for(int k=0;k<4;k++)s+=a[k*4+row]*b[col*4+k];c[col*4+row]=s;}return c;}
    private static void matrix(double[] e,DOMMatrixReadOnly a,String label){double[] v=values(a);for(int i=0;i<16;i++)near(e[i],v[i],1e-12,label+"["+i+"]");}
    private static void matrixIdentity(DOMMatrixReadOnly m,double eps,String label){double[] v=values(m);for(int i=0;i<16;i++)near((i%5)==0?1:0,v[i],eps,label+"["+i+"]");}
    private void pass(){passed++;}
    private static void yes(boolean x,String l){if(!x)throw new AssertionError(l);} private static void no(boolean x,String l){yes(!x,l);} private static void eq(double e,double a,String l){if(Double.doubleToLongBits(e)!=Double.doubleToLongBits(a))throw new AssertionError(l+": expected "+e+" got "+a);} private static void near(double e,double a,double eps,String l){if(Double.isNaN(e)?!Double.isNaN(a):Math.abs(e-a)>eps*Math.max(1,Math.max(Math.abs(e),Math.abs(a))))throw new AssertionError(l+": expected "+e+" got "+a);}
}
