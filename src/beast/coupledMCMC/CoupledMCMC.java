package beast.coupledMCMC;


import beast.core.*;
import beast.core.util.Log;
import beast.util.Randomizer;
import beast.util.XMLParser;
import beast.util.XMLProducer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

// Altekar G, Dwarkadas S, Huelsenbeck J and Ronquist F (2004). 
// Parallel Metropolis Coupled Markov Chain Monte Carlo For Bayesian Phylogenetic Inference.
// Bioinformatics, 20. ISSN 1367-4803, 
// http://dx.doi.org/10.1093/bioinformatics/btg427.

@Citation(value= "Altekar G, Dwarkadas S, Huelsenbeck J and Ronquist F (2004). \n" +
				"  Parallel Metropolis Coupled Markov Chain Monte Carlo For Bayesian Phylogenetic Inference.\n" +
				"  Bioinformatics, 20(3), 407-415."
		, year = 2004, firstAuthorSurname = "Altekar",
		DOI="10.1093/bioinformatics/btg427")
@Description("Metropolis-Coupled Markov Chain Monte Carlo" +
		"" +
		"Note that log file names should have $(seed) in their name so " +
		"that the first chain uses the actual seed in the file name and all subsequent chains add one to it." +
		"Furthermore, the log and tree log should have the same sample frequency.")
public class CoupledMCMC extends MCMC {
	public Input<Integer> nrOfChainsInput = new Input<Integer>("chains", " number of chains to run in parallel (default 2)", 2);
	public Input<Integer> resampleEveryInput = new Input<Integer>("resampleEvery", "number of samples in between resampling (and possibly swappping) states", 10000);
	public Input<String> tempDirInput = new Input<>("tempDir","directory where temporary files are written","");
	public Input<Double> temperatureScalerInput = new Input<>("temperatureScaler","temperature scaler, the higher this value, the hotter the chains",0.01);
	public Input<Boolean> logHeatedChainsInput = new Input<>("logHeatedChains","if true, log files for heated chains are also printed", false);

	
	
	// nr of samples between re-arranging states
	int resampleEvery;	
	double temperatureScaler;
	
	
	/** plugins representing MCMC with model, loggers, etc **/
	HeatedChain [] chains;
	/** threads for running MCMC chains **/
	Thread [] threads;
	/** keep track of time taken between logs to estimate speed **/
    long startLogTime;

	// keep track of when threads finish in order to optimise thread usage
	long [] finishTimes;

	List<StateNode> tmpStateNodes;

	@Override
	public void initAndValidate() {
		if (nrOfChainsInput.get() < 1) {
			throw new RuntimeException("chains must be at least 1");
		}
		if (nrOfChainsInput.get() == 1) {
			Log.warning.println("Warning: MCMCMC needs at least 2 chains to be effective, but chains=1. Running plain MCMC.");
		}
		// initialize the differently heated chains
		chains = new HeatedChain[nrOfChainsInput.get()];
		
		resampleEvery = resampleEveryInput.get();		
		temperatureScaler = temperatureScalerInput.get();
		
	} // initAndValidate
	
	private void initRun(){
		String sXML = new XMLProducer().toXML(this);
		sXML = sXML.replaceAll("chains=['\"][^ ]*['\"]", "");
		sXML = sXML.replaceAll("resampleEvery=['\"][^ ]*['\"]", "");
		sXML = sXML.replaceAll("tempDir=['\"][^ ]*['\"]", "");
		sXML = sXML.replaceAll("temperatureScaler=['\"][^ ]*['\"]", "");

	
        String sMCMCMC = this.getClass().getName();
		while (sMCMCMC.length() > 0) {
			sXML = sXML.replaceAll("\\b" + CoupledMCMC.class.getName() + "\\b", HeatedChain.class.getName());
			if (sMCMCMC.indexOf('.') >= 0) {
				sMCMCMC = sMCMCMC.substring(sMCMCMC.indexOf('.') + 1);
			} else {
				sMCMCMC = "";
			}
		}
		long nSeed = Randomizer.getSeed();
			

		
		// create new chains		
		for (int i = 0; i < chains.length; i++) {
			XMLParser parser = new XMLParser();
			String sXML2 = sXML;
			if (i>0){
				sXML2 = sXML2.replaceAll("fileName=\"", "fileName=\"chain" + i+ "");
			}
			
			try {
		        FileWriter outfile = new FileWriter(new File(tempDirInput.get() + stateFileName.replace("xml.state", "chain" + i + ".xml") ));
		        outfile.write(sXML2);
		        outfile.close();
				
				chains[i] = (HeatedChain) parser.parseFragment(sXML2, true);
	
				// remove all loggers, except for main chain
				if (i != 0 && !logHeatedChainsInput.get()) {
					for (int j = 0; j < chains[i].loggersInput.get().size(); j++){
						if (chains[i].loggersInput.get().get(j).getID().contentEquals("screenlog"))
							chains[i].loggersInput.get().remove(j);
						
//						chains[i].loggersInput.get().clear();
					}
					System.out.println(chains[i].loggersInput.get());
					System.exit(0);
				}			
				
				// initialize each chain individually
				chains[i].setChainNr(i, resampleEvery, temperatureScaler);
				chains[i].setStateFile(stateFileName.replace(".state", "." + i + "state"), restoreFromFile);				
				
				chains[i].run();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}		
		
		// get a copy of the list of state nodes to facilitate swapping states
		tmpStateNodes = startStateInput.get().stateNodeInput.get();

		chainLength = chainLengthInput.get();
		finishTimes = new long[chains.length];
	}
	
	
	
	class HeatedChainThread extends Thread {
		final int chainNr;
		HeatedChainThread(int chainNr) {
			this.chainNr = chainNr;
		}
		public void run() {
			try {
				finishTimes[chainNr] = chains[chainNr].runTillResample();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	@Override 
	public void run() throws IOException {
		
		initRun();
		
		int totalSwaps = 0;
		int successfullSwaps = 0, successfullSwaps0 = 0;

		for (int sampleNr = 0; sampleNr < chainLength; sampleNr += resampleEvery) {
			long startTime = System.currentTimeMillis();
			
			// start threads with individual chains here.
			threads = new Thread[chains.length];
			
			for (int k = 0; k < chains.length; k++) {
				threads[k] = new HeatedChainThread(k);
				threads[k].start();
			}

			// wait for the chains to finish
	        startLogTime = System.currentTimeMillis();
			for (Thread thread : threads) {
				try {
					thread.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			if (chains.length > 1) {
//			for (int dummy = 1; dummy < chains.length; dummy++){
				// resample state
				int i = Randomizer.nextInt(chains.length);
				int j = i;
				while (i == j) {
					j = Randomizer.nextInt(chains.length);
				}
				if (i > j) {
					int tmp = i; i = j; j = tmp;
				}
				
				
				double p1before = chains[i].getCurrentLogLikelihood();
				double p2before = chains[j].getCurrentLogLikelihood();
//				swapStates(chains[i], chains[j]);
//				double p1after = chains[i].calcCurrentLogLikelihoodRobustly();
//				double p2after = chains[j].calcCurrentLogLikelihoodRobustly();
				// robust calculations can be extremly expensive, just calculate the new probs instead 
				double p1after = chains[i].getCurrentLogLikelihood() * chains[i].getTemperature() / chains[j].getTemperature();
				double p2after = chains[j].getCurrentLogLikelihood() * chains[j].getTemperature() / chains[i].getTemperature();

				
//				System.out.println(p1after + " " + chains[i].getCurrentLogLikelihood() + " " + p2after + " " + chains[j].getCurrentLogLikelihood());
//				System.out.println(chains[i].getTemperature() + " " + chains[j].getTemperature()) ;
				
								
				double logAlpha = p1after - p1before + p2after - p2before;
				System.err.println(successfullSwaps0 + " " + successfullSwaps + ": " + i + " <--> " + j + ": " + logAlpha +  ": " + ((double) successfullSwaps/totalSwaps));
				if (Math.exp(logAlpha) < Randomizer.nextDouble()) {
					// swap fails
					//assignState(chains[i], chains[j]);
//					swapStates(chains[i], chains[j]);
					// not neeeded since the temperature scaler is only applied later
//					chains[i].calcCurrentLogLikelihoodRobustly();
//					chains[j].calcCurrentLogLikelihoodRobustly();

					
				} else {
					successfullSwaps++;
					if (i == 0) {
						successfullSwaps0++;
					}
					System.err.println(i + " <--> " + j);
					swapStates(chains[i], chains[j]);
					chains[i].calcCurrentLogLikelihoodRobustly();
					chains[j].calcCurrentLogLikelihoodRobustly();

					//assignState(chains[j], chains[i]);
					//chains[j].calcCurrentLogLikelihoodRobustly();
				}
				totalSwaps++;
				
				// tuning
				for (int k = 1; k < chains.length; k++) {
					chains[k].optimiseRunTime(startTime, finishTimes[k], finishTimes[0]);
				}
			}
		}

		System.err.println("#Successfull swaps = " + successfullSwaps);
		System.err.println("#Successfull swaps with cold chain = " + successfullSwaps0);
		// wait 5 seconds for the log to complete
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// ignore
		}
	} // run
	
	private void assignState(HeatedChain mcmc1, HeatedChain mcmc2) {
		State state1 = mcmc1.startStateInput.get();
		State state2 = mcmc2.startStateInput.get();
		List<StateNode> stateNodes1 = state1.stateNodeInput.get();
		List<StateNode> stateNodes2 = state2.stateNodeInput.get();
		for (int i = 0; i < stateNodes1.size(); i++) {
			StateNode stateNode1 = stateNodes1.get(i);
			StateNode stateNode2 = stateNodes2.get(i);
			stateNode1.assignFromWithoutID(stateNode2);
		}
	}

	/* swaps the states of mcmc1 and mcmc2 */
	void swapStates(MCMC mcmc1, MCMC mcmc2) {
		State state1 = mcmc1.startStateInput.get();
		State state2 = mcmc2.startStateInput.get();
		
		List<StateNode> stateNodes1 = state1.stateNodeInput.get();
		List<StateNode> stateNodes2 = state2.stateNodeInput.get();
		for (int i = 0; i < stateNodes1.size(); i++) {
			StateNode stateNode1 = stateNodes1.get(i);
			StateNode stateNode2 = stateNodes2.get(i);
			StateNode tmp = tmpStateNodes.get(i);
			tmp.assignFromWithoutID(stateNode1);
			stateNode1.assignFromWithoutID(stateNode2);
			stateNode2.assignFromWithoutID(tmp);
		}
	}
	
	
} // class MCMCMC



