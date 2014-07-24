package ir.index;

import ir.cluster.Frequency;
import ir.feature.SIFTExtraction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;

/**
 * Indexing and Searching runs locally using Solr
 */

public class Search {
	
	public static int featureSize = 128;
	
	public static int clusterNum=100;
	public static String clusters="test/cluster/clusters.txt";
	public static String terms="data/features/frequency.txt";
	
	public static void main(String[] args) throws IOException, SolrServerException{
		// run indexing
		runIndexing("test/data/frequency.txt");
		search("data/images/all_souls_000000.jpg");
	}
	
	public static void init(String terms, int clusterNum, String clusters){
		Search.terms = terms;
		Search.clusterNum = clusterNum;
		Search.clusters = clusters;
	}
	
	//TODO: code cleaning and add an entry point function
	public static void runIndexing(String terms){
		try {
			Indexing.index(terms);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SolrServerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void search(String image){
		try {
			System.out.println("test image: " + image);
			FileSystem fs = FileSystem.get(new Configuration());
			BufferedImage img = ImageIO.read(fs.open(new Path(image)));
			String[] features = SIFTExtraction.getFeatures(img);
			String qs = Search.createQuery(features);
			String[] results = query(qs);
			System.out.println("results length = " + results.length);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SolrServerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	public static String createQuery(String[] features) throws IOException{//transform an image into a Solr document or a field
		
		//String[] features = SIFTExtractor.extract(image);
		//System.out.println("query: " + image);
		double[][] clusters = Frequency.FEMap.readClusters(Search.clusters);
		int[] marks = new int[Search.clusterNum];
		
		for(int i = 0; i < features.length; i++){
			double[] feature = new double[Search.featureSize];
			String[] args = features[i].split(" ");
			for (int j = 0; j < Search.featureSize; j++)
				feature[j] = Double.parseDouble(args[j + 4]);
			int index = Frequency.FEMap.findBestCluster(feature, clusters);
			marks[index]++;
		}
		
		String result = "";
		for(int i = 0; i < Search.clusterNum; i++){
			for(int j = 0; j < marks[i]; j++){
				if(result.length() == 0) result += i;
				else result += " " + i;
			}	
		}
		//System.out.println("query string: " + result);
		return result;
	}
	
	public static String[] query(String s) throws SolrServerException{//query and output results
		
		//query a numeric vector as a string
		String urlString = "http://localhost:8983/solr";
		HttpSolrServer server = new HttpSolrServer(urlString);
		// search
	    SolrQuery query = new SolrQuery();
	    //query.setQuery("includes:" + s);
	    query.set("q", "includes:" + s);
	    
	    // get results		
	    QueryResponse qresponse = server.query(query);
	    SolrDocumentList list = qresponse.getResults();
	    String[] files = new String[list.size()];
	    for(int i = 0; i < list.size(); i++){
	    	System.out.println(list.get(i).getFieldValue("id"));
	    	files[i] = list.get(i).getFieldValue("id").toString();
	    }
	    
	    return files;
	}

}
