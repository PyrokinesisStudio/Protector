package co.protector.bot.core;

import co.protector.bot.ExitStatus;
import co.protector.bot.Main;
import co.protector.bot.core.settings.GuildSetting;
import co.protector.bot.core.settings.IGuildSettingType;
import co.protector.bot.core.settings.types.BooleanSettingType;
import co.protector.bot.core.settings.types.StringLengthSettingType;
import co.protector.bot.core.settings.types.TextChannelSettingType;
import co.protector.bot.util.Misc;
import net.dv8tion.jda.core.entities.Guild;
import org.bson.Document;
import org.reflections.Reflections;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Settings {
    private static ConcurrentHashMap<Long, GuildConfiguration> cache = new ConcurrentHashMap<>();
    private static HashMap<String, GuildSetting> settings = new HashMap<>();
    private static HashMap<Class<? extends GuildSetting>, String> classToName = new HashMap<>();

    static {
        init();
    }

    /**
     * retreives settings for a guild from cache or db
     *
     * @param guild the guild to retrieve for
     * @return cached settings
     */
    public static GuildConfiguration getSetting(Guild guild) {
        if (!cache.containsKey(guild.getIdLong())) {
            cache.put(guild.getIdLong(), loadGuild(guild));
        }
        return cache.get(guild.getIdLong());
    }

    /**
     * remove guild from cache
     *
     * @param guild the guild to remove
     */
    public static void clearCache(Guild guild) {
        cache.remove(guild.getIdLong());
    }

    /**
     * load settings from the db
     *
     * @param guild the guild to retrieve for
     * @return settings
     */
    private static GuildConfiguration loadGuild(Guild guild) {
        GuildConfiguration gc = new GuildConfiguration();
        for (Map.Entry<String, GuildSetting> s : settings.entrySet()) {
            String varname = s.getKey();
            GuildSetting setting = s.getValue();
            try {
                Field field = gc.getClass().getField(varname);
                Object value;
                value = Database.getDocument(guild.getId(), setting.dbTable()).get("value");
                if (value == null) {
                    value = setting.getDefault();
                }
                if (field.get(gc) instanceof Boolean) {
                    field.set(gc, Misc.isFuzzyTrue(String.valueOf(value)));
                } else {
                    field.set(gc, value);
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return gc;
    }

    private static void init() {
        Reflections reflections = new Reflections("co.protector.bot.settings");
        Set<Class<? extends GuildSetting>> classes = reflections.getSubTypesOf(GuildSetting.class);
        for (Class<? extends GuildSetting> s : classes) {
            try {
                if (Modifier.isAbstract(s.getModifiers())) {
                    continue;
                }
                GuildSetting setting = s.getConstructor().newInstance();
                settings.put(setting.getKey(), setting);
                classToName.put(setting.getClass(), setting.getKey());
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
                Main.exit(ExitStatus.CONFIG_INITIALIZATION);
            }
        }
    }

    public static boolean update(Guild guild, Class<? extends GuildSetting> configClass, String value) {
        return update(guild, classToName.get(configClass), value);
    }

    public static boolean update(Guild guild, String config, String value) {
        GuildSetting cfg = settings.get(config);
        IGuildSettingType settingType = cfg.getSettingTypeObject();
        if (!cfg.isValidValue(guild, value)) {
            return false;
        }
        String writeValue = cfg.getValue(guild, value);
        if (settingType instanceof BooleanSettingType) {
            Database.saveConfigField(cfg.dbTable(), new Document().append("value", Misc.isFuzzyTrue(writeValue)), guild.getId());
        } else if (settingType instanceof StringLengthSettingType || settingType instanceof TextChannelSettingType) {
            Database.saveConfigField(cfg.dbTable(), new Document().append("value", writeValue), guild.getId());
        } else {
            return false;
        }
        clearCache(guild);
        return true;
    }
}
