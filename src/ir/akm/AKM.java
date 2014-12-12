package ir.akm;

import ir.util.HadoopUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.common.distance.EuclideanDistanceMeasure;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;

// map-reduce implementation of AKM using random KDTree forest
public class AKM {
	public static final int dim = 128;
	int maxIterations = 100;
	int cluster_num = 100*100;
	double ConvergenceDelta = 0.1;
	DistanceMeasure dm = new EuclideanDistanceMeasure();
	
	//test main
	public static void main(String args[]) throws Exception{
		///test use
		HadoopUtil.delete("test_akm_MR");
		//normalize the features
		normalize("test_fe_seq2seq_100images/data/features", "test_akm_MR/normalizedfeatures/seq");
		
		AKM akm = new AKM();
		akm.runClustering("test_akm_MR/normalizedfeatures", "test_akm_MR");
		
	}
	
	private static void normalize(String in_folder, String out_file) 
			throws IOException, InstantiationException, IllegalAccessException {
		// TODO Auto-generated method stub
		String[] files = HadoopUtil.getListOfFiles(in_folder);
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, new Path(out_file), Text.class,VectorWritable.class);
		for(String file : files){
			SequenceFile.Reader reader = new SequenceFile.Reader(fs, new Path(file), conf);
			Text key =(Text) reader.getKeyClass().newInstance();
			VectorWritable value = (VectorWritable) reader.getValueClass().newInstance();
			double[] arr = new double[dim];
			
			while(reader.next(key, value)){
				double sum = 0;
				for(int i = 0; i < dim; i ++){
					arr[i] = value.get().get(i);
					sum = sum + arr[i]*arr[i];
				}
				sum = Math.sqrt(sum);
				for(int i = 0; i < dim; i ++){
					arr[i] = arr[i] / sum;
				}
				Vector vector = new DenseVector(arr);
				value.set(vector);
				writer.append(key, value);
			}
			reader.close();
			
		}
		writer.close();
	}

	/*entry point of AKM clustering
	 *@PARAM input_dataset: input folder of extracted features
	 *@PARAM output: the cluster result output folder
	 */
	public void runClustering(String input_dataset, String output) 
			throws Exception{
		Configuration conf = new Configuration();
		// get the inital clusters
		Path initial_cluster_path = new Path(output + "/0/0");
		akm_local.clusters_init_random(input_dataset, initial_cluster_path, cluster_num, conf , true);
		
		// run iterations
		// run akm iteraterations until maximam iterations reached or cd reached
		int iteration_num = 0;
		while(iteration_num < maxIterations){
			System.out.println("AKM iteration : " + (iteration_num + 1));
			String clusters_in = output + "/" + iteration_num;
			String clusters_out = output + "/" + (iteration_num + 1);
			
			runIteration(input_dataset, clusters_in, clusters_out);
			
			iteration_num ++;
			// eliminate empty clusters, fill them with the old clusters
			eliminate_empty_clusters(clusters_out, clusters_in);

			// check if the clusters has converged or not
			if(isConverged(clusters_out + "/isConverged") == true){
				break;
			}
		}
		//convert the cluster centroids to a txt file "clusters.txt"
		getFinalResult(conf, output + "/" + iteration_num, output + "/clusters.txt", input_dataset);
		
	}
 


	//running one iteration of akm
	// @PARAM: input_dataset: the features extracted
	// @PARAM: clusters_in : the current clusters centroids path
	// @PARAM: clusters_out : the output new clusters path
	private void runIteration(String input_dataset, String clusters_in, String clusters_out)
			throws IOException, ClassNotFoundException, InterruptedException {
			// TODO Auto-generated method stub
			Configuration conf = new Configuration();
			conf.set("K", "" + cluster_num);
			conf.set("clusters_in", clusters_in);
			conf.set("features", input_dataset);
			conf.set("cd", "" + ConvergenceDelta);
			conf.set("clusters_out", clusters_out);
			
			Job job = new Job(conf);
			
			job.setJarByClass(AKM.class);
			job.setMapperClass(AKM.AKM_Mapper.class);
			job.setReducerClass(AKM.AKM_Reducer.class);
			
			job.setOutputKeyClass(IntWritable.class);
			job.setOutputValueClass(VectorWritable.class);
			
			job.setInputFormatClass(SequenceFileInputFormat.class);
			job.setOutputFormatClass(SequenceFileOutputFormat.class);
			//job.setOutputFormatClass(SequenceFileOutputFormat.class);
			
			FileInputFormat.addInputPath(job, new Path(input_dataset));
			FileOutputFormat.setOutputPath(job, new Path(clusters_out));
			job.waitForCompletion(true);
		
	}
	
	// read in the old clusters, assign each feature to a cluster based on random kdtree foest NNS
	public static class AKM_Mapper extends  Mapper<Text, VectorWritable, IntWritable, VectorWritable> {
		public static KDTreeForest kdtf= null;
	//	public static final int dim = 128;
		public static int K = 0; //number of clusters 
		public static String clusters_in= null; // input folder of clusters
		
		public static double[][] varray = null; // clusters array
		public static double[] q_vector = new double[AKM.dim];
		static IntWritable out_key = new IntWritable();
		static VectorWritable out_value = new VectorWritable();
		static int nnid = 0;
		
		@Override
		public void setup( Context context) throws IOException {
			Configuration conf=context.getConfiguration();
			K = Integer.parseInt(conf.get("K"));
			clusters_in = conf.get("clusters_in");
//			String features = conf.get("features");
			
			//TODO
			//read in the clusters and store then in the varry
			try {
				varray = getClustersFromPath(conf, clusters_in, K);
			} catch (InstantiationException e) {
				// T Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TOD Auto-generated catch block
				e.printStackTrace();
			}
			
			//TODO
			// construct kdtree forest
			kdtf = new KDTreeForest();
			kdtf.build_forest(varray);
		}
		@Override
		public void map(Text key, VectorWritable value, Context context) 
				throws IOException, InterruptedException {	
			// for each feature in the dataset, assign to the nearest neighbor using kdtree forest
			for(int i = 0; i < dim; i ++){
				q_vector[i] = value.get().get(i);
			}
			try {
				 nnid= kdtf.nns_BBF(varray, q_vector);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				nnid = -1; //to cause errors to detect
				e.printStackTrace();
			}
			out_key.set(nnid);
			Vector v = new DenseVector(q_vector);
			out_value.set(v);
			context.write(out_key, out_value);
		}
	}
	
	// calculate the new clusters based on the newly assigned features
	// will output to output/isConverged folder  if converged or not(if all the clusters have converged, no files will be output to this folder)
	// output/processed_clusters will contain those new clusters(that have features assigned to them), can be used to find the empty clusters
	public static class AKM_Reducer extends  Reducer<IntWritable, VectorWritable, IntWritable, VectorWritable> {
		static VectorWritable out_value = new VectorWritable();
		static Vector vector = new DenseVector(new double[dim]);
		static int K = 0;
		static String clusters_in = null;
		static double[][] old_clusters = null; // clusters array
		static ArrayList<Integer> processed_clusters = null;
		static boolean isConverged = true;
		static double cd = 0;
		static String clusters_out = null;
		
		@Override
		public void setup( Context context) throws IOException {
			Configuration conf=context.getConfiguration();
			K = Integer.parseInt(conf.get("K"));
			clusters_in = conf.get("clusters_in");
			clusters_out = conf.get("clusters_out");
			cd = Double.parseDouble(conf.get("cd"));
			isConverged = true;
			processed_clusters = new ArrayList<Integer>();
			
			//TODO
			//read in the clusters and store then in the varry
			try {
				old_clusters = getClustersFromPath(conf, clusters_in, K);
			} catch (InstantiationException e) {
				// T Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TOD Auto-generated catch block
				e.printStackTrace();
			}
		}

		
		public void reduce(IntWritable key, Iterable<VectorWritable> values, Context context) 
				throws IOException, InterruptedException {
			int num_values = 0;
			double[] new_cluster = new double[dim];
			for(int i = 0; i < dim; i ++){
				new_cluster[i] = 0;
			}
			for(VectorWritable vw : values){
				num_values ++;
				for(int i = 0; i < dim; i ++){
					new_cluster[i] += vw.get().get(i) ;
				}
			}
			for(int i = 0; i < dim; i ++){
				new_cluster[i] = new_cluster[i] / num_values;
			}
			vector.assign(new_cluster);
			out_value.set(vector);
			context.write(key, out_value);
			
			//check if the cluster have converged or not
			//and add the cluster id to the processed_clusters 
			processed_clusters.add(key.get());
			try {
				if(RandomizedKDtree.getDistance(new_cluster, old_clusters[key.get()]) > cd){
					isConverged = false;
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		  }
		
		// output the isConverge value to output/isConverged---
		//only output if false, i.e. there are unconverged clusters in this clusters processed by this reducer
		// output the processed_clusters to output/processed_clusters
		@Override
		protected void cleanup(Context context) throws IOException {
			if(processed_clusters.size() > 0){
				Configuration conf = context.getConfiguration();
				FileSystem fs =FileSystem.get(conf);
				String isConverged_path = clusters_out + "/isConverged/";
				String processed_clusters_path = clusters_out + "/processed_clusters/";
				HadoopUtil.mkdir(isConverged_path);
				HadoopUtil.mkdir(processed_clusters_path);
				if(isConverged == false){
					FSDataOutputStream writer = fs.create(new Path(isConverged_path + "/" + processed_clusters.get(0)));
					writer.writeChars("false");
					writer.close();
				}
				SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, new Path(processed_clusters_path + "/" + processed_clusters.get(0)), 
						IntWritable.class,IntWritable.class);
				IntWritable clusterId = new IntWritable();
				for(Integer i : processed_clusters){
					clusterId.set(i);
					writer.append(clusterId, clusterId);
				}
				writer.close();
				
			}
		}
	}
	
	// read the processed_clusters and get those empty clusters, set them as the old clusters ids 
	private void eliminate_empty_clusters(String clusters_out,	String clusters_in) 
			throws IOException, InstantiationException, IllegalAccessException {
		// TODO Auto-generated method stub
		Configuration conf = new Configuration();
		
		// read the files in "processed_clusters", get the empty cluster ids
		ArrayList<Integer> empty_clusterIds = new ArrayList<Integer>();
		boolean[] processed = new boolean[cluster_num];
		for(int i = 0; i < processed.length; i ++){
			processed[i] = false;
		}
		
		String processed_clusters = clusters_out + "/processed_clusters";
		String[] files = HadoopUtil.getListOfFiles(processed_clusters);
		for(String file : files){
			SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf), new Path(file), conf);
			IntWritable key =(IntWritable) reader.getKeyClass().newInstance();
			IntWritable value = (IntWritable) reader.getValueClass().newInstance();
			while(reader.next(key, value)){
				if(processed[key.get()] == true){
					reader.close();
					throw new IOException("duplicate clusterID !!!" + key.get());
					
				}
				else{
					processed[key.get()] = true;
				}
			}
			reader.close();
		}
		//get empty clusters
		for(int i = 0; i < processed.length; i ++){
			if(processed[i] == false){
				empty_clusterIds.add(i);
			}
		}
		//if there is no empty clusters, can directly return
		if(empty_clusterIds.size() == 0){
			return;
		}
		else {
			System.out.println("empty_clusters exist  " + empty_clusterIds.size());
			double[][] old_clusters =getClustersFromPath( conf, clusters_in, cluster_num) ;
			//
			SequenceFile.Writer writer = new SequenceFile.Writer(FileSystem.get(conf), conf, new Path(clusters_out + "/emptyclusters"),
					IntWritable.class,VectorWritable.class);
			for(int i : empty_clusterIds){
				writer.append(new IntWritable(i), new VectorWritable(new DenseVector(old_clusters[i])));
			}
			writer.close();
		}
	}

	//check if the output  is converged or not
	//only need to examinne if cluster_out/isConverged is empty folder or not: empty -- converged, else not converged
	private boolean isConverged(String isConvergedFolder) 
			throws Exception {
		// TODO Auto-generated method stub
		
		String[] files = HadoopUtil.getListOfFiles(isConvergedFolder);
		if(files == null || files.length == 0)
			return true;
		else
			return false;
	}

	// get the final result to file "clusters.txt"
	private void getFinalResult(Configuration conf, String inputfolder,	String outputfile, String features_folder) 
			throws IOException, InstantiationException, IllegalAccessException {
		// TODO Auto-generated method stub
		FileSystem fs =FileSystem.get(conf);
		//get the clusters in to mem.
		double[][] clusters = getClustersFromPath(conf, inputfolder, cluster_num);
		FSDataOutputStream writer = fs.create(new Path(outputfile));
		for(int i = 0; i < clusters.length; i ++){
			StringBuilder sb=new StringBuilder();
			sb.append("" + i + "\t" + new DenseVector(clusters[i]).toString() + "\n");
			byte[] byt=sb.toString().getBytes();
			writer.write(byt);
		}
		writer.flush();
		writer.close();
		
	}
	
	// read in the clusters into double[][] from a folder of sequencefile
	// the sequencefile should have the key/value = IntWritable/VectorWritable, where the key is the cluster ID
	// will check for duplicate cluster ID and empty cluster ID
	private static double[][] getClustersFromPath(Configuration conf, String clusters_in2, int k2) 
			throws IOException, InstantiationException, IllegalAccessException {
		// TODO Auto-generated method stub
		double[][] dataset = new double[k2][dim];
		String[] files = HadoopUtil.getListOfFiles(clusters_in2);
		
		boolean[] flag = new boolean[k2];
		for(int i = 0; i < k2; i ++){
			flag[i] = false;
		}
		
		for(String file : files){
			SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.get(conf), new Path(file), conf);
			IntWritable key =(IntWritable) reader.getKeyClass().newInstance();
			VectorWritable value = (VectorWritable) reader.getValueClass().newInstance();
			// the cluster ID, should put cluster in the IDth slot
			int index = 0;
			while(reader.next(key, value)){
				index = key.get();
				
				//test
				if(flag[index] == false){
					flag[index] = true;
				}
				else{
					reader.close();
					throw new IOException("duplicate cluster ID");
				}
				//
				dataset[index] = new double[128];
				Vector v = value.get();
				for(int i = 0; i < 128; i ++){
					dataset[index][i] = v.get(i);
				}
			}
			reader.close();
		}
		//check we have all clusters
		for(int i = 0; i < k2; i ++){
			// this cluster has not been assigned any features, need to re-initialize it
			if(flag[i] == false) {
				throw new IOException("empty cluster ID: " + i);
			}
		}
		
		return dataset;
	}

}
