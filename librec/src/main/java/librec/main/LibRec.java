// Copyright (C) 2014 Guibing Guo
//
// This file is part of LibRec.
//
// LibRec is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// LibRec is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with LibRec. If not, see <http://www.gnu.org/licenses/>.
//

package librec.main;

import happy.coding.io.Configer;
import happy.coding.io.FileIO;
import happy.coding.io.Logs;
import happy.coding.io.Strings;
import happy.coding.io.net.EMailer;
import happy.coding.math.Maths;
import happy.coding.system.Dates;
import happy.coding.system.Systems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import librec.baseline.ConstantGuess;
import librec.baseline.GlobalAverage;
import librec.baseline.ItemAverage;
import librec.baseline.MostPopular;
import librec.baseline.RandomGuess;
import librec.baseline.UserAverage;
import librec.baseline.UserCluster;
import librec.data.DataDAO;
import librec.data.DataSplitter;
import librec.data.MatrixEntry;
import librec.data.SparseMatrix;
import librec.ext.AR;
import librec.ext.External;
import librec.ext.Hybrid;
import librec.ext.NMF;
import librec.ext.PD;
import librec.ext.PRankD;
import librec.ext.SlopeOne;
import librec.intf.Recommender;
import librec.intf.Recommender.Measure;
import librec.ranking.BPR;
import librec.ranking.CLiMF;
import librec.ranking.FISMauc;
import librec.ranking.FISMrmse;
import librec.ranking.GBPR;
import librec.ranking.LDA;
import librec.ranking.RankALS;
import librec.ranking.RankSGD;
import librec.ranking.SBPR;
import librec.ranking.SLIM;
import librec.ranking.WBPR;
import librec.ranking.WRMF;
import librec.rating.BPMF;
import librec.rating.BiasedMF;
import librec.rating.ItemKNN;
import librec.rating.PMF;
import librec.rating.RSTE;
import librec.rating.RegSVD;
import librec.rating.SVDPlusPlus;
import librec.rating.SoRec;
import librec.rating.SoReg;
import librec.rating.SocialMF;
import librec.rating.TimeSVD;
import librec.rating.TrustMF;
import librec.rating.TrustSVD;
import librec.rating.UserKNN;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;

/**
 * Main Class of the LibRec Library
 * 
 * @author guoguibing
 * 
 */
public class LibRec {
	// version: MAJOR version (significant changes), followed by MINOR version (small changes, bug fixes)
	protected static String version = "1.3";

	// configuration
	protected static Configer cf;
	protected static String configFile = "librec.conf";
	protected static String algorithm;

	protected static double binThold;

	// rate DAO object
	protected static DataDAO rateDao;

	// rating matrix
	protected static SparseMatrix rateMatrix = null;

	/**
	 * entry of the LibRec library
	 * 
	 * @param args
	 *            command line arguments
	 */
	public static void main(String[] args) {

		try {
			// process librec arguments
			cmdLine(args);

			// get configuration file
			cf = new Configer(configFile);

			// prepare data
			binThold = cf.getDouble("val.binary.threshold");
			rateDao = new DataDAO(cf.getPath("dataset.training"));
			rateMatrix = rateDao.readData(binThold);

			// config general recommender
			Recommender.cf = cf;
			Recommender.rateMatrix = rateMatrix;
			Recommender.rateDao = rateDao;

			// run algorithms
			runAlgorithm();

			// collect results to folder "Results"
			String destPath = FileIO.makeDirectory("Results");
			String results = destPath + algorithm + "@" + Dates.now() + ".txt";
			FileIO.copyFile("results.txt", results);

			// send notification
			notifyMe(results);

		} catch (Exception e) {
			// capture exception to log file
			Logs.error(e.getMessage());

			e.printStackTrace();
		}
	}

	/**
	 * process arguments specified at the command line
	 * 
	 * @param args
	 *            command line arguments
	 */
	protected static void cmdLine(String[] args) throws Exception {
		// read arguments
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-c")) { // configuration file
				configFile = args[i + 1];
				return;
			} else if (args[i].equals("-v")) { // print out short version information
				System.out.println("LibRec version " + version);
			} else if (args[i].equals("--version")) { // print out full version information
				readMe();
			} else if (args[i].equals("--dataset-spec")) {
				// print out data set specification
				cf = new Configer(configFile);

				DataDAO rateDao = new DataDAO(cf.getPath("dataset.training"));
				rateDao.printSpecs();

				String socialSet = cf.getPath("dataset.social");
				if (!socialSet.equals("-1")) {
					DataDAO socDao = new DataDAO(socialSet, rateDao.getUserIds());
					socDao.printSpecs();
				}

				String testSet = cf.getPath("dataset.testing");
				if (!testSet.equals("-1")) {
					DataDAO testDao = new DataDAO(testSet, rateDao.getUserIds(), rateDao.getItemIds());
					testDao.printSpecs();
				}
			} else if (args[i].equals("--dataset-split")) {
				// split the training data set into "train-test" or "train-validation-test" subsets
				cf = new Configer(configFile);

				rateDao = new DataDAO(cf.getPath("dataset.training"));
				rateMatrix = rateDao.readData();

				// format: (1) train-ratio; (2) train-ratio validation-ratio
				double trainRatio = Double.parseDouble(args[i + 1]);
				boolean isValidationUsed = (args.length > i + 2) && Maths.isNumeric(args[i + 2]);
				double validRatio = isValidationUsed ? Double.parseDouble(args[i + 2]) : 0;

				if (trainRatio <= 0 || validRatio < 0 || (trainRatio + validRatio) >= 1) {
					throw new Exception(
							"Wrong format! Accepted formats are either '-dataset-split ratio' or '-dataset-split trainRatio validRatio'");
				}

				// split data
				DataSplitter ds = new DataSplitter(rateMatrix);
				SparseMatrix[] results = isValidationUsed ? ds.getRatio(trainRatio, validRatio) : ds
						.getRatio(trainRatio);

				// write out
				String dirPath = FileIO.makeDirectory(rateDao.getDataDirectory(), "split");
				writeMatrix(results[0], dirPath + "training.txt");

				if (isValidationUsed) {
					writeMatrix(results[1], dirPath + "validation.txt");
					writeMatrix(results[2], dirPath + "test.txt");
				} else {
					writeMatrix(results[1], dirPath + "test.txt");
				}
			}
			System.exit(0);
		}
	}

	/**
	 * write a matrix data into a file
	 */
	private static void writeMatrix(SparseMatrix data, String filePath) throws Exception {
		// delete old file first
		FileIO.deleteFile(filePath);

		List<String> lines = new ArrayList<>(1500);
		for (MatrixEntry me : data) {
			int u = me.row();
			int j = me.column();
			double ruj = me.get();

			if (ruj <= 0)
				continue;

			String user = rateDao.getUserId(u);
			String item = rateDao.getItemId(j);

			lines.add(user + " " + item + " " + (float) ruj);

			if (lines.size() >= 1000) {
				FileIO.writeList(filePath, lines, true);
				lines.clear();
			}
		}

		if (lines.size() > 0)
			FileIO.writeList(filePath, lines, true);

		Logs.debug("Matrix data is written to: {}", filePath);
	}

	/**
	 * prepare training and test data, and then run a specified recommender
	 * 
	 */
	protected static void runAlgorithm() throws Exception {

		// validation method
		String validationMethod = cf.getString("validation.method");
		String settings = cf.getString("validation.settings");

		// debug information
		StringBuilder debugInfo = new StringBuilder();
		debugInfo.append("With Test by ").append(validationMethod);

		if (!Recommender.isRankingPred) {
			String view = cf.getString("rating.pred.view");
			switch (view.toLowerCase()) {
			case "cold-start":
				debugInfo.append(", ").append(view);
				break;
			case "trust-degree":
				debugInfo.append(String.format(", %s [%d, %d]",
						new Object[] { view, cf.getInt("min.trust.degree"), cf.getInt("max.trust.degree") }));
				break;
			case "all":
			default:
				break;
			}
		}

		Recommender algo = null;

		DataSplitter ds = new DataSplitter(rateMatrix);
		SparseMatrix[] data = null;

		int N;
		double ratio;

		switch (validationMethod.toLowerCase()) {
		case "cv":
			runCrossValidation(settings, debugInfo);
			return; // make it close
		case "leave-one-out":
			runLeaveOneOut(Integer.parseInt(settings), debugInfo);
			return; //
		case "test-set":
			Logs.debug(debugInfo.toString());
			debugInfo = new StringBuilder();
			debugInfo.append(String.format("Testing: %s, ", Strings.last(settings, 38)));

			DataDAO testDao = new DataDAO(settings, rateDao.getUserIds(), rateDao.getItemIds());
			SparseMatrix testMatrix = testDao.readData(binThold);
			data = new SparseMatrix[] { rateMatrix, testMatrix };
			break;
		case "given-n":
			N = Integer.parseInt(settings);
			debugInfo.append(": ").append(N);

			data = ds.getGiven(N);
			break;
		case "given-ratio":
			ratio = Double.parseDouble(settings);
			debugInfo.append(": ").append(ratio);

			data = ds.getGiven(ratio);
			break;
		case "train-ratio":
		default:
			ratio = Double.parseDouble(settings);
			debugInfo.append(": ").append(ratio);

			data = ds.getRatio(Double.parseDouble(settings));
			break;
		}

		Logs.debug(debugInfo.toString());

		algo = getRecommender(data, -1);
		algo.execute();

		printEvalInfo(algo, algo.measures);
	}

	private static void runCrossValidation(String settings, StringBuilder debugInfo) throws Exception {

		String[] sets = settings.split("[,\t ]");
		int kFold = Integer.parseInt(sets[0]);
		debugInfo.append(": ").append(kFold).append(" Folds ");

		boolean isParallelFold = true;
		if (sets.length >= 2) {
			switch (sets[1]) {
			case "fold-by-fold":
				isParallelFold = false;
				break;
			case "parallel-fold":
			default:
				isParallelFold = true;
				break;
			}
		}
		debugInfo.append(isParallelFold ? "[Parallel]" : "[Singleton]");
		Logs.debug(debugInfo.toString());

		DataSplitter ds = new DataSplitter(rateMatrix, kFold);

		Thread[] ts = new Thread[kFold];
		Recommender[] algos = new Recommender[kFold];

		for (int i = 0; i < kFold; i++) {
			Recommender algo = getRecommender(ds.getKthFold(i + 1), i + 1);

			algos[i] = algo;
			ts[i] = new Thread(algo);
			ts[i].start();

			if (!isParallelFold)
				ts[i].join();
		}

		if (isParallelFold)
			for (Thread t : ts)
				t.join();

		// average performance of k-fold
		Map<Measure, Double> avgMeasure = new HashMap<>();
		for (Recommender algo : algos) {
			for (Entry<Measure, Double> en : algo.measures.entrySet()) {
				Measure m = en.getKey();
				double val = avgMeasure.containsKey(m) ? avgMeasure.get(m) : 0.0;
				avgMeasure.put(m, val + en.getValue() / kFold);
			}
		}

		printEvalInfo(algos[0], avgMeasure);
	}

	/**
	 * interface to run Leave-one-out approach
	 */
	private static void runLeaveOneOut(int numThreads, StringBuilder debugInfo) throws Exception {

		assert numThreads > 0;

		debugInfo.append(" [").append(numThreads).append(" Threads]");
		Logs.debug(debugInfo.toString());

		Thread[] ts = new Thread[numThreads];
		Recommender[] algos = new Recommender[numThreads];

		// average performance of k-fold
		Map<Measure, Double> avgMeasure = new HashMap<>();

		int rows = rateMatrix.numRows();
		int cols = rateMatrix.numColumns();

		int count = 0;
		for (MatrixEntry me : rateMatrix) {
			double rui = me.get();
			if (rui <= 0)
				continue;

			int u = me.row();
			int i = me.column();

			// leave the current rating out
			SparseMatrix trainMatrix = new SparseMatrix(rateMatrix);
			trainMatrix.set(u, i, 0);

			// build test matrix
			Table<Integer, Integer, Double> dataTable = HashBasedTable.create();
			Multimap<Integer, Integer> colMap = HashMultimap.create();
			dataTable.put(u, i, rui);
			colMap.put(i, u);
			SparseMatrix testMatrix = new SparseMatrix(rows, cols, dataTable, colMap);

			// get a recommender
			Recommender algo = getRecommender(new SparseMatrix[] { trainMatrix, testMatrix }, count + 1);

			algos[count] = algo;
			ts[count] = new Thread(algo);
			ts[count].start();

			if (numThreads == 1) {
				ts[count].join(); // fold by fold

				for (Entry<Measure, Double> en : algo.measures.entrySet()) {
					Measure m = en.getKey();
					double val = avgMeasure.containsKey(m) ? avgMeasure.get(m) : 0.0;
					avgMeasure.put(m, val + en.getValue());
				}
			} else if (count < numThreads) {
				count++;
			}

			if (count == numThreads) {
				// parallel fold
				for (Thread t : ts)
					t.join();
				count = 0;

				// record performance
				for (Recommender algo2 : algos) {
					for (Entry<Measure, Double> en : algo2.measures.entrySet()) {
						Measure m = en.getKey();
						double val = avgMeasure.containsKey(m) ? avgMeasure.get(m) : 0.0;
						avgMeasure.put(m, val + en.getValue());
					}
				}
			}
		}

		// normalization
		int size = rateMatrix.size();
		for (Entry<Measure, Double> en : avgMeasure.entrySet()) {
			Measure m = en.getKey();
			double val = en.getValue();
			avgMeasure.put(m, val / size);
		}

		printEvalInfo(algos[0], avgMeasure);
	}

	/**
	 * print out the evaluation information for a specific algorithm
	 */
	private static void printEvalInfo(Recommender algo, Map<Measure, Double> ms) {

		String result = Recommender.getEvalInfo(ms);
		// we add quota symbol to indicate the textual format of time 
		String time = String.format("'%s','%s'", Dates.parse(ms.get(Measure.TrainTime).longValue()),
				Dates.parse(ms.get(Measure.TestTime).longValue()));
		String evalInfo = String.format("%s,%s,%s,%s", algo.algoName, result, algo.toString(), time);

		Logs.info(evalInfo);
	}

	/**
	 * Send a notification of completeness
	 * 
	 * @param attachment
	 *            email attachment
	 */
	protected static void notifyMe(String attachment) throws Exception {

		String hostInfo = FileIO.getCurrentFolder() + "." + algorithm + " [" + Systems.getIP() + "]";

		if (!cf.isOn("is.email.notify")) {
			System.out.println("Program " + hostInfo + " has completed!");
			return;
		}

		EMailer notifier = new EMailer();
		Properties props = notifier.getProps();

		props.setProperty("mail.debug", "false");

		String host = cf.getString("mail.smtp.host");
		String port = cf.getString("mail.smtp.port");
		props.setProperty("mail.smtp.host", host);
		props.setProperty("mail.smtp.port", port);
		props.setProperty("mail.smtp.auth", cf.getString("mail.smtp.auth"));

		props.put("mail.smtp.socketFactory.port", port);
		props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

		final String user = cf.getString("mail.smtp.user");
		final String pwd = cf.getString("mail.smtp.password");
		props.setProperty("mail.smtp.user", user);
		props.setProperty("mail.smtp.password", pwd);

		props.setProperty("mail.from", user);
		props.setProperty("mail.to", cf.getString("mail.to"));

		props.setProperty("mail.subject", hostInfo);
		props.setProperty("mail.text", "Program was completed @" + Dates.now());

		String msg = "Program [" + algorithm + "] has completed !";
		notifier.send(msg, attachment);
	}

	/**
	 * @return a recommender to be run
	 */
	private static Recommender getRecommender(SparseMatrix[] data, int fold) throws Exception {

		SparseMatrix trainMatrix = data[0], testMatrix = data[1];
		algorithm = cf.getString("recommender");

		switch (algorithm.toLowerCase()) {

		/* baselines */
		case "globalavg":
			return new GlobalAverage(trainMatrix, testMatrix, fold);
		case "useravg":
			return new UserAverage(trainMatrix, testMatrix, fold);
		case "itemavg":
			return new ItemAverage(trainMatrix, testMatrix, fold);
		case "usercluster":
			return new UserCluster(trainMatrix, testMatrix, fold);
		case "random":
			return new RandomGuess(trainMatrix, testMatrix, fold);
		case "constant":
			return new ConstantGuess(trainMatrix, testMatrix, fold);
		case "mostpop":
			return new MostPopular(trainMatrix, testMatrix, fold);

			/* rating prediction */
		case "userknn":
			return new UserKNN(trainMatrix, testMatrix, fold);
		case "itemknn":
			return new ItemKNN(trainMatrix, testMatrix, fold);
		case "regsvd":
			return new RegSVD(trainMatrix, testMatrix, fold);
		case "biasedmf":
			return new BiasedMF(trainMatrix, testMatrix, fold);
		case "svd++":
			return new SVDPlusPlus(trainMatrix, testMatrix, fold);
		case "timesvd++":
			return new TimeSVD(trainMatrix, testMatrix, fold);
		case "pmf":
			return new PMF(trainMatrix, testMatrix, fold);
		case "bpmf":
			return new BPMF(trainMatrix, testMatrix, fold);
		case "socialmf":
			return new SocialMF(trainMatrix, testMatrix, fold);
		case "trustmf":
			return new TrustMF(trainMatrix, testMatrix, fold);
		case "sorec":
			return new SoRec(trainMatrix, testMatrix, fold);
		case "soreg":
			return new SoReg(trainMatrix, testMatrix, fold);
		case "rste":
			return new RSTE(trainMatrix, testMatrix, fold);
		case "trustsvd":
			return new TrustSVD(trainMatrix, testMatrix, fold);

			/* item ranking */
		case "climf":
			return new CLiMF(trainMatrix, testMatrix, fold);
		case "fismrmse":
			return new FISMrmse(trainMatrix, testMatrix, fold);
		case "fism":
		case "fismauc":
			return new FISMauc(trainMatrix, testMatrix, fold);
		case "rankals":
			return new RankALS(trainMatrix, testMatrix, fold);
		case "ranksgd":
			return new RankSGD(trainMatrix, testMatrix, fold);
		case "wrmf":
			return new WRMF(trainMatrix, testMatrix, fold);
		case "bpr":
			return new BPR(trainMatrix, testMatrix, fold);
		case "wbpr":
			return new WBPR(trainMatrix, testMatrix, fold);
		case "gbpr":
			return new GBPR(trainMatrix, testMatrix, fold);
		case "sbpr":
			return new SBPR(trainMatrix, testMatrix, fold);
		case "slim":
			return new SLIM(trainMatrix, testMatrix, fold);
		case "lda":
			return new LDA(trainMatrix, testMatrix, fold);

			/* extension */
		case "nmf":
			return new NMF(trainMatrix, testMatrix, fold);
		case "hybrid":
			return new Hybrid(trainMatrix, testMatrix, fold);
		case "slopeone":
			return new SlopeOne(trainMatrix, testMatrix, fold);
		case "pd":
			return new PD(trainMatrix, testMatrix, fold);
		case "ar":
			return new AR(trainMatrix, testMatrix, fold);
		case "prankd":
			return new PRankD(trainMatrix, testMatrix, fold);
		case "external":
			return new External(trainMatrix, testMatrix, fold);

		default:
			throw new Exception("No recommender is specified!");
		}
	}

	/**
	 * Print out software information
	 */
	private static void readMe() {
		String readme = "\nLibRec version " + version + ", copyright (C) 2014-2015 Guibing Guo \n\n"

		/* Description */
		+ "LibRec is a free software: you can redistribute it and/or modify \n"
				+ "it under the terms of the GNU General Public License as published by \n"
				+ "the Free Software Foundation, either version 3 of the License, \n"
				+ "or (at your option) any later version. \n\n"

				/* Usage */
				+ "LibRec is distributed in the hope that it will be useful, \n"
				+ "but WITHOUT ANY WARRANTY; without even the implied warranty of \n"
				+ "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the \n"
				+ "GNU General Public License for more details. \n\n"

				/* licence */
				+ "You should have received a copy of the GNU General Public License \n"
				+ "along with LibRec. If not, see <http://www.gnu.org/licenses/>.";

		System.out.println(readme);
	}
}
