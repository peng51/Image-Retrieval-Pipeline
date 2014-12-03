package ir.akm;

import ir.feature.FeatureExtraction_seq;
import ir.feature.SIFTExtraction;
import ir.util.HadoopUtil;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

// forest of KDTrees for approximate nearest neighbor search
public class KDTreeForest {
	public static final int num_trees = 32;
	public static final int num_dimensions = 30;
	public static double max_comparison = 0.05;// suggestion: should set this to about 5% to 15 % of of the total nodes???
	
	public Node[] roots = null;
	
	/*
	 * build a forest of kd trees
	 * choose num_total_dimensions as the dimensions to split on
	 * build num_trees of trees
	 * 
	 * */
	public void build_forest(ArrayList<double[]>  varray){
		this.roots = new Node[num_trees];
		int[] dims = getTopDimensionsWithLargestVariance(num_dimensions, varray);
		for(int i = 0; i < num_trees; i ++){
			RandomizedKDtree rt = new RandomizedKDtree();
			roots[i] = rt.buildTree(dims, varray);
		}
	};
	public int getNearestNeighborId(ArrayList<double[]> varray, double[] q_vector) throws Exception{
		//initialize global variables --  vector[0] as the current nearest neighbor
		NNS nn = new NNS();
		nn.nnId = 0;
		nn.minDistance = RandomizedKDtree.getDistance(varray.get(nn.nnId), q_vector);
		nn.comparisons = 0;
		nn_recursive(roots,  varray, nn, q_vector);
		
		System.out.println("number of comparisons  " + nn.comparisons);
		return nn.nnId;
		
	}
	public void nn_recursive(Node[] nodes, ArrayList<double[]> varray, NNS nn, double[] q_vector) throws Exception{
		//check the nodes first
		boolean all_null_nodes = true;
		for(Node node : nodes){
			//not null nodes, should have a list of points that can be compared to
			if(node != null){
				all_null_nodes = false;
				for(int i : node.points){
					
					//get the distance of the query vector and the new element in the array
					double dist = RandomizedKDtree.getDistance(q_vector, varray.get(i));
					
					if( dist < nn.minDistance){
						nn.nnId = i;
						nn.minDistance = dist;
						System.out.println(nn.nnId + "\t" + nn.minDistance);
					}
					nn.comparisons ++;
					
					if(nn.comparisons >= varray.size() * max_comparison){
						return;
					}
				}
			}
		}
		if(all_null_nodes == true){
			return;
		}
		
		//check the children of the nodes (if there exists)
		Node[] nodes_first = new Node[nodes.length];
		Node[] nodes_last = new Node[nodes.length];
		for(int i = 0; i < nodes.length; i ++){
			nodes_first[i] = null;
			nodes_last[i] = null;
		}
		all_null_nodes = true;
		for(int i = 0; i < nodes.length; i ++){
			//check if node[i] is null, skip if its null
			if(nodes[i] != null){
				//internal node case
				if(nodes[i].left != null || nodes[i].right != null){
					all_null_nodes = false;
					// examine if query node position to the hyperplane of the node
					//left first
					if(q_vector[nodes[i].split_axis] < nodes[i].split_value){
						nodes_first[i] = nodes[i].left;
						nodes_last[i] = nodes[i].right;
					}
					//right first
					else{
						nodes_first[i] = nodes[i].right;
						nodes_last[i] = nodes[i].left;
					}
				}
			}
			
		}
		if(all_null_nodes == true){
			return;
		}
		// recurive search the children sub tree that the q_vector belongs to
		nn_recursive(nodes_first, varray, nn,  q_vector);
		// recursively search the other sub trees if necessary
		for(int i = 0; i < nodes.length; i ++){
			//skip null nodes AND Leaf Nodes !!!!!
			if(nodes[i] != null && nodes_last[i] != null){
				// if the distance in this dimension is already larger than minDistance, no need to check it anymore
				if(Math.abs(q_vector[nodes[i].split_axis] - nodes[i].split_value) > nn.minDistance){
					nodes_last[i] = null;
				}
			}
		}
		nn_recursive(nodes_last, varray, nn,  q_vector);
		
	}
	

	//get the top num_d dimensions with the largest variance for splitting the space
	public static int[] getTopDimensionsWithLargestVariance(int num_d, ArrayList<double[]> varray) {
		// TODO Auto-generated method stub
		int[] dims = new int[num_d];
		double[] variance_num_d = new double[num_d];
		
		double[] all_variances = new double[varray.get(0).length];
		
		for(int k = 0; k < num_d; k ++){
			variance_num_d[k] = 0;
		}
		
		for(int i = 0; i < varray.get(0).length; i ++){
			
			//calc variance for dim = i
			double variance = 0;
			double mean = 0;
			for(int j = 0; j < varray.size(); j++){
				variance = variance + varray.get(j)[i] * varray.get(j)[i];
				mean = mean + varray.get(j)[i];
			}
			mean = mean / varray.size();
			variance = variance - varray.size() * mean * mean;
			
			all_variances[i] = variance;
			//update the dims array
			int k = 0;
			for(k = 0 ; k < dims.length; k ++){
				if(variance > variance_num_d[k])
					break;
			}
			for(int s = dims.length - 1; s > k; s --){
				dims[s] = dims[s - 1];
				variance_num_d[s] = variance_num_d[s - 1];
			}
			if(k < dims.length){
				dims[k] = i;
				variance_num_d[k] = variance;
			}
			
		}
		
		
		return dims;
	}
	
	///test main
	// get sift features from the images and write them to file.
	public static void get_features() throws IOException{
		ArrayList<double[]> varray = new ArrayList<double[]>();
		String[] files = HadoopUtil.getListOfFiles("data/images");
		FileSystem fs = FileSystem.get(new Configuration());
		
		for(String file : files){
			BufferedImage img = ImageIO.read(fs.open(new Path(file)));
			System.out.println("extracting sift features from " + file);
			String[] features = SIFTExtraction.getFeatures(img);
			for(String feature : features){
				double[] feature_vector = FeatureExtraction_seq.FEMap.getPoints(feature.split(" "), 128);

					varray.add(feature_vector);
			}
		}
	}
	public static void main(String args[]) throws Exception{
		int dim = 128;
		
	//	get_features();
		
		Random rand = new Random();
		//get sift features from images folder
		ArrayList<double[]> q_vectors = new ArrayList<double[]>();
		ArrayList<double[]> varray = new ArrayList<double[]>();
		BufferedReader br = new BufferedReader(new FileReader(new File("data/features_images.txt")));
		String inline = null;
		while((inline = br.readLine()) != null){
			String[] splits = inline.split(" ");
			double[] vector = new double[128];
			for(int i = 0; i < 128; i ++ ){
				vector[i] = Double.parseDouble(splits[i]);
			}
			
			if(q_vectors.size() < 1000){
				q_vectors.add(vector);
			}
			else{
				varray.add(vector);
			}
			
			
		}
		br.close();
		
		System.out.println(varray.size());
		//double[][] marks={{1.0,2,3,4,5},{10.0,9,8,7,6},{5.0,5,5,5,5}};
/*		for(int i = 0; i < 100000; i ++){
			double[] arr = new double[dim];
			for (int j = 0; j < arr.length; j ++){
				arr[j] = rand.nextDouble() * (rand.nextInt(100) + 1);
			}
			varray.add(arr);
		}
		*/
		
	/*	int[] points =  {0, 1, 2};
		double median = getApproximateMedian(varray, points, 0);
		System.out.println(median);
		*/
		KDTreeForest kdtf = new KDTreeForest();
		kdtf.build_forest(varray);
		
		double precision = 0;
		double speedup = 0;
		//test the precision and the avg speed up
		for(double[] q_vector : q_vectors){
			
			//generate random query vector
/*			double[] arr = new double[dim];
			for (int j = 0; j < arr.length; j ++){
				arr[j] = rand.nextDouble() * (rand.nextInt(100) + 1);
			}
			
			double[]  q_vector = arr;
*/
			
			long startTime = System.nanoTime();
			int id = kdtf.getNearestNeighborId(varray, q_vector);
			long endTime = System.nanoTime();
			double kdtf_time = ((double)( endTime - startTime )/(1000 * 1000 * 1000));
//			System.out.println("Kdtree  forest nns finished in " + kdtf_time + "secs \n"
//					+ " distance " + RandomizedKDtree.getDistance(q_vector, varray.get(id)));
			
			
			
			//serial way to find the nearest vector
			long startTime1 = System.nanoTime();
			double min_dist = Double.MAX_VALUE;
			int nnId = -1;
			for(int i = 0; i < varray.size(); i ++){
				double dist = RandomizedKDtree.getDistance(varray.get(i), q_vector);
				
				if(dist < min_dist){
					nnId = i;
					min_dist = dist;
				}
			}
			long endTime1 = System.nanoTime();
			
			double exact_search_time = ((double)( endTime1 - startTime1)/(1000 * 1000 * 1000));
//			System.out.println("serial nns finished in " + exact_search_time + "secs ,\n "
//					+ "nnID : " +  nnId + "   distance: " + min_dist );
			
			if (id == nnId){
				precision ++;
			}
			speedup = speedup + exact_search_time/kdtf_time;
		}
		speedup = speedup / q_vectors.size();
		precision = precision / q_vectors.size();
		System.out.println(varray.size());
		System.out.println("avg precision : " + precision + " avg speedup : " + speedup + ""
				+ "\t for " + num_trees + " trees  and " + num_dimensions + " dmensions and maximum comparisons : " + max_comparison );
	}

}
