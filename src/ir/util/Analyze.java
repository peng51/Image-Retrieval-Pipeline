package ir.util;

import java.util.ArrayList;
import java.util.List;
import java.io.*;

//code to analyze the output from "hadoop job -list"
//args0: input of recordcontainers.txt
// 
//to be added
//args 1 $FEStart$ 1409027930563
//args 2 $FEEnd$   1409027930563
//args 3 $VWStart$ 1409027930563
//args 4 $VWEnd$   1409027930563
//args 5  $ISStart$ 1409027930563
//args 6 $ISEnd$  1409027930563

public class Analyze {

	public static void main(String args[]){
		String logfile=args[0];
		String result_file="results_new.txt";
		List<snapshot> snapshots=getSnapshots(logfile);
		WriteResults(result_file,snapshots);
		
	}

	private static void WriteResults(String result_file, List<snapshot> snapshots) {
		// TODO Auto-generated method stub
		try {
			BufferedWriter bw=new BufferedWriter(new FileWriter(new File(result_file)));
			for(snapshot s:snapshots){
				System.out.println(s.timestamp+","+s.num_containers+","+s.usedmem+"\n");
				bw.write(s.timestamp+","+s.num_containers+","+s.usedmem+"\n");
			}
			bw.flush();
			bw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private static List<snapshot> getSnapshots(String logfile) {
		// TODO Auto-generated method stub
		List<snapshot> snapshots=new ArrayList<snapshot>();
		BufferedReader br=null;
		try {
			br=new BufferedReader(new FileReader(new File(logfile)));
			String line=null;
			long timestamp=0;
			int totaljobs=0;
			while((line=br.readLine())!=null){
				if(line.trim().startsWith("TimeStamp")){//possibly a usable snapshot
					timestamp=Long.parseLong((line.split(":"))[1].trim());
					line=br.readLine();
					totaljobs=Integer.parseInt((line.split(":"))[1].trim());
					if(totaljobs==0){//no snap shots
						line=br.readLine();
					}
					else{//snapshots, but might not be the one we want, need further test
						line=br.readLine();//read the comment line
						int UsedContainers=0;
						int UsedMem=0;
						for(int i=0;i<totaljobs;i++){
							line=br.readLine();
							String splits[]=line.split("\\s+");
							if(splits[4].trim().equals("ypeng")){
								String s=splits[9].trim();
								s = s.substring(0, s.length() - 1);
								UsedContainers+=Integer.parseInt(splits[7].trim());
								UsedMem+=Integer.parseInt(s);
							}
						}
						
						snapshot ss=new snapshot(timestamp, UsedContainers,UsedMem);
						snapshots.add(ss);
						
					}
				}
				
			}
			br.close();
			return snapshots;
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		return null;
	}
}

class snapshot{
	long  timestamp;
	//String user;
	int num_containers;
	int usedmem;
	public snapshot(long timestamp, int num_containers, int usedmem){
		this.timestamp=timestamp;
//		this.user=user;
		this.num_containers=num_containers;
		this.usedmem=usedmem;
	}
}