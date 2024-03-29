package org.bj.deeplearning.dataobjects;

import org.bj.deeplearning.tools.ImageTool;
import org.bj.deeplearning.tools.PropertiesReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FileSystem {

	private static final Random RNG = new Random();
	private static final String TRAINING_DATA_FOLDER = "trainingdata";
	private static final String FEATURE_FOLDER = "features";
	private static final String PIXEL_DATA_FOLDER = "pixeldata";
	private static final String MODELS_FOLDER = "models";
	private static final String CONTINUOUS_FOLDER = "continuous";
	private static final String SAMPLES_FOLDER = "samples";
	private static final String FEATURE_MAPS_FOLDER = "featuremaps";
	public static final String MODEL_FILE_NAME = "model";
	public static final Pattern modelIdPattern = Pattern.compile("^model(\\d+)\\.bin$");

	static {
		RNG.setSeed(Long.parseLong(PropertiesReader.getProjectProperties().getProperty("training.seed")));
	}

	public static void persist(int trainSize, int validationSize, int testSize, int batchSize, boolean checkIntegrity) {
		int maxNumberOfImagesToPersist = trainSize + validationSize + testSize;
		persistImagesToDisk(batchSize, maxNumberOfImagesToPersist);
		if(PropertiesReader.getProjectProperties().getProperty("training.persistence.checkIntegrity").equals("true")) {
			persistMissingImages(maxNumberOfImagesToPersist);
		}
	}

	private static void persistImagesToDisk(int batchSize, int maxNumberOfImagesToPersist) {
		int maxSavedId = FileSystem.findLatestTrainingDataId();
		int maxId = TrainingDataHandler.getTotalNumberOfImages();

		if(maxNumberOfImagesToPersist < maxSavedId) {
			maxNumberOfImagesToPersist = maxSavedId;
		}

		if(maxId > maxNumberOfImagesToPersist) {
			maxId = maxNumberOfImagesToPersist;
		}

		if(maxSavedId != maxId) {
			System.out.println("Downloading " + (maxId - maxSavedId) + " instances of training data");
		}

		for(int i = maxSavedId + 1; i <= maxId; i += batchSize) {
			if((i - maxSavedId - 1)%(10*batchSize) == 0 && i != maxSavedId + 1) {
				System.out.format("Downloaded %d items \n", i - maxSavedId - 1);
			}
			List<TrainingData> images = TrainingDataHandler.getTrainingData(i, i + batchSize - 1);
			FileSystem.persist(images);
		}
	}

	private static void persistMissingImages(int maxNumberOfImagesToPersist) {
		int maxSavedId = FileSystem.findLatestTrainingDataId();
		int maxId = TrainingDataHandler.getTotalNumberOfImages();

		if(maxNumberOfImagesToPersist < maxSavedId) {
			maxNumberOfImagesToPersist = maxSavedId;
		}

		if(maxId > maxNumberOfImagesToPersist) {
			maxId = maxNumberOfImagesToPersist;
		}

		System.out.println("Checking integrity of locally stored data");

		for(int i = 1; i <= maxId; i++) {
			if(!FileSystem.exists(i)) {
				List<TrainingData> images = TrainingDataHandler.getTrainingData(i, i);
				FileSystem.persist(images);
				if(FileSystem.exists(i)) {
					System.out.format("Successfully downloaded locally missing training data with id %d \n", i);
				} else {
					System.out.format("Tried to download locally missing training data with id %d, but the data was not persisted to file system. Try reassigning database ids. \n", i);
				}
			}
		}
	}

	public static List<TrainingData> getRandomImages(int amount) {
		List<Integer> range = IntStream.rangeClosed(1, findLatestTrainingDataId()).boxed().collect(Collectors.toList());
		Collections.shuffle(range, RNG);
		return load(range.parallelStream().limit(amount));
	}

	public static void persist(List<TrainingData> tds) {
		tds.parallelStream().forEach(td -> persist(td));
	}

	public static void persist(TrainingData td) {

		double angle = td.getAngle();
		double speed = td.getSpeed();

		double marking_L;
		if (td.getType() == TrainingDataType.EXTENSIVE)
			 marking_L = td.getMarking_L();
		double marking_M = td.getMarking_M();
		double marking_R =  td.getMarking_R();

		double dist_L;
		double dist_R;

		if (td.getType() == TrainingDataType.EXTENSIVE){
			dist_L = td.getMarking_L();
			dist_R = td.getMarking_R();

		}

		int height = td.getHeight();
		int width = td.getWidth();
		int id = td.getId();
		byte[] pixelData = td.getPixelData();

		StringBuilder sb = new StringBuilder();
		sb.append(angle).append(System.lineSeparator());
		sb.append(speed).append(System.lineSeparator());
		sb.append(marking_M).append(System.lineSeparator());
		sb.append(marking_R).append(System.lineSeparator());
		sb.append(width).append(System.lineSeparator());
		sb.append(height).append(System.lineSeparator());

		try {
			Files.write(getPixelDataPath(id), pixelData, StandardOpenOption.CREATE);
			Files.write(getFeaturePath(id), sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
		} catch(IOException e) {
			e.printStackTrace();
			System.exit(31);
		}
	}

	public static List<TrainingData> load(int idFromInclusive, int idToInclusive) {
		return load(IntStream.rangeClosed(idFromInclusive, idToInclusive).boxed());
	}

	public static List<TrainingData> load(List<Integer> ids) {
		return load(ids.parallelStream());
	}

	private static List<TrainingData> load(Stream<Integer> ids ) {
		return ids.map(id -> load(id)).collect(Collectors.toList());
	}

	public static TrainingData load(int id) {
		try {
			return new TrainingData(id, TrainingDataType.MINIMAL, TrainingDataHandler.runType);

		} catch(Exception e) {
			e.printStackTrace();
			System.exit(31);
		}
		return null;
	}

	public static int findLatestTrainingDataId() {
		if(!Files.exists(getFeatureFolder())) {
			return 0;
		}
		try {
			return Files.walk(getFeatureFolder())
					.filter(p -> isTrainingFile(p))
					.map(file -> Integer.valueOf(file.getFileName().toString()))
					.reduce(0, (curMax, cur) -> Math.max(curMax, cur));
		} catch(IOException e) {
			e.printStackTrace();
			System.exit(1);
			return 0;
		}
	}

	public static int findLatestModelId() {
		if(!Files.exists(getContinuousFolder())) {
			return 0;
		}
		try {
			return Files.walk(getContinuousFolder())
					.filter(Files::isRegularFile)
					.map(file -> extractModelId(file.getFileName().toString()))
					.reduce(0, (curMax, cur) -> Math.max(curMax, cur));
		} catch(IOException e) {
			e.printStackTrace();
			System.exit(1);
			return 0;
		}
	}

	public static boolean isTrainingFile(Path path) {
		try {
			return Files.isRegularFile(path) && !Files.isHidden(path);
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static int extractModelId(String fileName) {
		Matcher matcher = modelIdPattern.matcher(fileName);
		if(matcher.matches()) {
			return Integer.parseInt(matcher.group(1));
		}
		return 0;
	}

	public static Path getPathOfLatestModelFile() {
		return getContinuousFolder().resolve(Paths.get(getModelFileName(findLatestModelId())));
	}

	public static Path getPathOfModelFile(int numeration) {
		return getContinuousFolder().resolve(Paths.get(getModelFileName(numeration)));
	}

	public static void createFolders() throws IOException {
		Files.createDirectories(getFeatureMapsFolder());
		Files.createDirectories(getSamplesFolder());
		Files.createDirectories(getModelsFolder());
		Files.createDirectories(getContinuousFolder());
		Files.createDirectories(getTrainingDataFolder());
		Files.createDirectories(getFeatureFolder());
		Files.createDirectories(getPixelDataFolder());
	}

	public static void createModelsFolders() throws IOException {

		Files.createDirectories(getModelsFolder());
		Files.createDirectories(getContinuousFolder());
	}

	private static Path getFeaturePath(int id) {
		return getFeatureFolder().resolve(Integer.toString(id));
	}

	private static Path getPixelDataPath(int id) {
		return getPixelDataFolder().resolve(Integer.toString(id));
	}

	private static Path getFeatureFolder() {
		return getTrainingDataFolder().resolve(FEATURE_FOLDER);
	}

	private static Path getPixelDataFolder() {
		return getTrainingDataFolder().resolve(PIXEL_DATA_FOLDER);
	}

	private static Path getTrainingDataFolder() {
		return Paths.get(TRAINING_DATA_FOLDER);
	}

	public static Path getSamplesFolder() {
		return Paths.get(SAMPLES_FOLDER);
	}

	public static Path getFeatureMapsFolder() {
		return Paths.get(FEATURE_MAPS_FOLDER);
	}

	public static Path getModelsFolder() {
		return Paths.get(MODELS_FOLDER);
	}

	public static Path getContinuousFolder() {
		return getModelsFolder().resolve(CONTINUOUS_FOLDER);
	}

	public static String getModelFileName(int numeration) {
		return FileSystem.MODEL_FILE_NAME + numeration +".bin";
	}

	public static Path getFeatureMapsFolderForLayer(int layer) throws IOException {
		Path forLayer = getFeatureMapsFolder().resolve(new Integer(layer).toString());
		Files.createDirectories(forLayer);
		return forLayer;
	}

	public static boolean exists(int fileId) {
		boolean pixelDataExists = Files.exists(getPixelDataPath(fileId));
		boolean featureDataExists = Files.exists(getFeaturePath(fileId));
		if(pixelDataExists ^ featureDataExists) {
			throw new IllegalStateException("Downloaded feature or pixel data without the other.");
		}
		return pixelDataExists && featureDataExists;
	}
}
