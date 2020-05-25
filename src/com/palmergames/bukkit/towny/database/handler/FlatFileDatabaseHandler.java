package com.palmergames.bukkit.towny.database.handler;

import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.database.handler.annotations.LoadSetter;
import com.palmergames.bukkit.towny.exceptions.AlreadyRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.database.Saveable;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.utils.ReflectionUtil;
import com.palmergames.util.FileMgmt;
import org.apache.commons.lang.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

public class FlatFileDatabaseHandler extends DatabaseHandler {
	
	private static final FilenameFilter filenameFilter = (dir, name) -> name.endsWith(".txt");
	private final Map<Class<?>, File> fileDirectoryCache = new HashMap<>();

	@Override
	public void save(@NotNull Saveable obj) {
		// Validation safety
		Validate.notNull(obj);
		Validate.notNull(obj.getSaveDirectory(), "You must specify a save path for class: " + obj.getClass().getName());
		
		HashMap<String, String> saveMap = new HashMap<>();

		// Get field data.
		convertMapData(ReflectionUtil.getObjectMap(obj), saveMap);
		
		// Add save getter data.
		convertMapData(getSaveGetterData(obj), saveMap);
		
		// Save
		FileMgmt.mapToFile(saveMap, new File(obj.getSaveDirectory().getPath() + "/" + obj.getUniqueIdentifier() + ".txt"));
	}

	private <T extends Saveable> @Nullable File getFlatFileDirectory(@NotNull Class<T> type) {
		
		// Check the cache
		File cached = fileDirectoryCache.get(type);
		if (fileDirectoryCache.get(type) != null) {
			return cached;
		}

		boolean hasUUIDConstructor = true;
		Constructor<T> objConstructor = null;
		// First try the natural constructor
		try {
			objConstructor = type.getConstructor(UUID.class);
		} catch (NoSuchMethodException e) {
			hasUUIDConstructor = false;
		}

		Saveable saveable;
		if (!hasUUIDConstructor) {
			// If there is no UUID constructor we need to rely
			// on unsafe allocation to bypass any defined constructors
			saveable = ReflectionUtil.unsafeNewInstance(type);
		} else {
			try {
				saveable = objConstructor.newInstance((Object) null);
			} catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
				e.printStackTrace();
				return null;
			}
		}

		if (saveable == null) {
			return null;
		}
		
		// Cache result.
		fileDirectoryCache.computeIfAbsent(type, (t) -> saveable.getSaveDirectory());

		return saveable.getSaveDirectory();
	}

	@Override
	public boolean delete(@NotNull Saveable obj) {
		Validate.notNull(obj);
		
		File objFile = obj.getSaveDirectory();
		if (objFile.exists()) {
			return objFile.delete();
		} else {
			TownyMessaging.sendErrorMsg("Cannot delete: " + objFile + ", it does not exist.");
			return false;
		}
		
	}
	
	@Nullable
	public <T> T load(File file, @NotNull Class<T> clazz) {
		Constructor<T> objConstructor = null;
		try {
			objConstructor = clazz.getConstructor(UUID.class);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		T obj = null;
		try {
			Validate.isTrue(objConstructor != null);
			obj = objConstructor.newInstance((Object) null);
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			e.printStackTrace();
		}

		Validate.isTrue(obj != null);
		List<Field> fields = ReflectionUtil.getAllFields(obj, true);

		HashMap<String, String> values = loadFileIntoHashMap(file);
		for (Field field : fields) {
			Type type = field.getGenericType();
			Class<?> classType = field.getType();
			field.setAccessible(true);

			String fieldName = field.getName();

			if (values.get(fieldName) == null) {
				continue;
			}

			Object value;

			if (ReflectionUtil.isPrimitive(type)) {
				value = loadPrimitive(values.get(fieldName), type);
			} else if (field.getType().isEnum()) {
				value = ReflectionUtil.loadEnum(values.get(fieldName), classType);
			} else {
				value = fromStoredString(values.get(fieldName), type);
			}

			if (value == null) {
				// ignore it as another already allocated value may be there.
				continue;
			}

			LoadSetter loadSetter = field.getAnnotation(LoadSetter.class);

			try {

				if (loadSetter != null) {
					Method method = obj.getClass().getMethod(loadSetter.setterName(), field.getType());
					method.invoke(obj, value);
				} else {
					field.set(obj, value);
				}
				
			} catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
				e.printStackTrace();
				return null;
			}
		}
		
		return obj;
	}

	public HashMap<String, String> loadFileIntoHashMap(File file) {
		HashMap<String, String> keys = new HashMap<>();
		try (FileInputStream fis = new FileInputStream(file);
			 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
			Properties properties = new Properties();
			properties.load(isr);
			for (String key : properties.stringPropertyNames()) {
				String value = properties.getProperty(key);
				keys.put(key, String.valueOf(value));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return keys;
	}
	
	// ---------- Loaders ----------
	
	@Override
	public void loadAllResidents() {
		loadFiles(Resident.class, (resident -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addResident(resident);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	@Override
	public void loadAllWorlds() {
		loadFiles(TownyWorld.class, (world) -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addWorld(world);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		});
	}

	@Override
	public void loadAllNations() {
		loadFiles(Nation.class, (nation -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addNation(nation);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	@Override
	public void loadAllTowns() {
		loadFiles(Town.class, town -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addTown(town);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		});
	}

	@Override
	public void loadAllTownBlocks() {
		loadFiles(TownBlock.class, (tb -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addTownBlock(tb);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	// ---------- Loaders ----------
	
	private <T extends Saveable> void loadFiles(@NotNull Class<T> clazz, @NotNull Consumer<T> consumer) {
		File dir = getFlatFileDirectory(clazz);
		
		// This must be non-null
		if (dir == null) {
			throw new UnsupportedOperationException("Directory does not exist");
		}
		
		// Make sure that a file wasn't given instead of a directory
		if (!dir.isDirectory()) {
			throw new UnsupportedOperationException("Object of type: " + clazz + " has save path is not a directory.");
		}

		// Iterate through all files
		for (File file : dir.listFiles(filenameFilter)) {
			T loadedObj = load(file, clazz);
			
			// Log any errors but continue loading.
			if (loadedObj == null) {
				TownyMessaging.sendErrorMsg("Could not load " + file.getName());
				continue;
			}
			
			// Consume the loaded object.
			consumer.accept(loadedObj);
		}
	}

	private void convertMapData(Map<String, ObjectContext> from, Map<String, String> to) {
		for (Map.Entry<String, ObjectContext> entry : from.entrySet()) {
			String valueStr = toStoredString(entry.getValue().getValue(), entry.getValue().getType());
			to.put(entry.getKey(), valueStr);
		}
	}
}
