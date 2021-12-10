package org.janelia.saalfeldlab.hotknife;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;

import picocli.CommandLine.Option;

public class TestSparkSerialization {

    @Option(names = {"-i", "--n5Path"})
    public String n5Path;

    public void go() {
//        final String n5Local = n5Path;
        final N5WriterSupplier n5Supplier = (N5WriterSupplier & Serializable)( () -> new N5FSWriter( n5Path ) );

        final SparkConf conf = new SparkConf()
                .setMaster("local[1]")
                .setAppName("TestSparkSerialization");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);
        final List<Integer> testList = Collections.singletonList(1);
        final JavaRDD<Integer> testRdd = sparkContext.parallelize(testList);
        testRdd.map(testIndex -> {
            n5Supplier.get();
            return null;
        });
        testRdd.collect();
        sparkContext.close();
    }

    public static void main(String[] args) {
        new TestSparkSerialization().go();
    }
}
