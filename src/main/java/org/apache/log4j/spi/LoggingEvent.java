package org.apache.log4j.spi;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.JBossLogManagerFacade;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.jboss.modules.Module;

import org.apache.log4j.Category;
import org.apache.log4j.JBossLevelMapping;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.apache.log4j.helpers.Loader;
import org.apache.log4j.helpers.LogLog;

/**
 * Our LoggingEvent class which is designed to look and act just like log4j's, but which maintains
 * an internal {@link org.jboss.logmanager.ExtLogRecord} instance.
 */
public class LoggingEvent implements Serializable {

    // Use the same UID with the same visibility as the original
    @SuppressWarnings("SerialVersionUIDWithWrongSignature")
    static final long serialVersionUID = -868428216207166145L;

    private static final Field logRecordField;
    private static final Field fqnOfCategoryClassField;

    // Pre-sorted to speed class serialization analysis
    private static final ObjectStreamField[] serialPersistentFields = new ObjectStreamField[] {
            new ObjectStreamField("categoryName", String.class),
            new ObjectStreamField("locationInfo", LocationInfo.class),
            new ObjectStreamField("mdcCopy", Hashtable.class),
            new ObjectStreamField("mdcCopyLookupRequired", boolean.class),
            new ObjectStreamField("ndc", String.class),
            new ObjectStreamField("ndcLookupRequired", boolean.class),
            new ObjectStreamField("renderedMessage", String.class),
            new ObjectStreamField("timeStamp", long.class),
            new ObjectStreamField("threadName", String.class),
            new ObjectStreamField("throwableInfo", ThrowableInformation.class),
    };

    private static long startTime;

    static {
        long time = 0L;
        try {
            time = Module.getStartTime();
        } catch (Throwable ignored) {
            time = System.currentTimeMillis();
        }
        startTime = time;
        logRecordField = getField("logRecord");
        fqnOfCategoryClassField = getField("fqnOfCategoryClass");
    }

    //// Public fields

    // Serialized
    public final String categoryName;
    public final long timeStamp;

    // Transient
    public final transient String fqnOfCategoryClass;
    public transient Priority level;

    private transient final org.jboss.logmanager.LogContext logContext;
    private transient Category logger;

    //// Private fields

    // Transient
    private final transient ExtLogRecord logRecord;
    private transient ThrowableInformation cachedThrowableInformation;
    private transient LocationInfo cachedLocationInfo;

    static final Integer[] PARAM_ARRAY = new Integer[1];
    static final String TO_LEVEL = "toLevel";
    static final Class[] TO_LEVEL_PARAMS = new Class[] { int.class };
    static final Hashtable methodCache = new Hashtable(3);

    // Match the API constructors

    public LoggingEvent(String fqnOfCategoryClass, Category logger, Priority level, Object message, Throwable throwable) {
        this.fqnOfCategoryClass = fqnOfCategoryClass;
        this.logger = logger;
        this.level = level;
        logRecord = new ExtLogRecord(JBossLevelMapping.getLevelFor(level), message == null ? null : message.toString(), ExtLogRecord.FormatStyle.NO_FORMAT, fqnOfCategoryClass);
        if (logger != null) {
            logRecord.setLoggerName(categoryName = logger.getName());
        } else {
            categoryName = null;
        }
        if (throwable != null) {
            logRecord.setThrown(throwable);
        }
        logRecord.setMillis(timeStamp = System.currentTimeMillis());
        logContext = null;
    }

    public LoggingEvent(String fqnOfCategoryClass, Category logger, long timeStamp, Priority level, Object message, Throwable throwable) {
        this.fqnOfCategoryClass = fqnOfCategoryClass;
        this.logger = logger;
        this.level = level;
        logRecord = new ExtLogRecord(JBossLevelMapping.getLevelFor(level), message == null ? null : message.toString(), ExtLogRecord.FormatStyle.NO_FORMAT, fqnOfCategoryClass);
        if (logger != null) {
            logRecord.setLoggerName(categoryName = logger.getName());
        } else {
            categoryName = null;
        }
        if (throwable != null) {
            logRecord.setThrown(throwable);
        }
        logRecord.setMillis(this.timeStamp = timeStamp);
        logContext = null;
    }

    public LoggingEvent(final String fqnOfCategoryClass, final Category logger, final long timeStamp, final Level level, final Object message, final String threadName, final ThrowableInformation throwable, final String ndc, final LocationInfo info, final java.util.Map properties) {
        this.fqnOfCategoryClass = fqnOfCategoryClass;
        this.logger = logger;
        this.level = level;
        logRecord = new ExtLogRecord(JBossLevelMapping.getLevelFor(level), message == null ? null : message.toString(), ExtLogRecord.FormatStyle.NO_FORMAT, fqnOfCategoryClass);
        if (logger != null) {
            logRecord.setLoggerName(categoryName = logger.getName());
        } else {
            categoryName = null;
        }
        if (throwable != null) {
            logRecord.setThrown((cachedThrowableInformation = throwable).getThrowable());
        }

        this.timeStamp = timeStamp;
        if (threadName != null) {
            logRecord.setThreadName(threadName);
        }
        if (ndc != null) {
            logRecord.setNdc(ndc);
        }
        if (info != null) {
            cachedLocationInfo = info;
            logRecord.setSourceClassName(info.getClassName());
            logRecord.setSourceMethodName(info.getMethodName());
            logRecord.setSourceFileName(info.getFileName());
            try {
                logRecord.setSourceLineNumber(Integer.parseInt(info.getLineNumber(), 10));
            } catch (NumberFormatException ignored) {
                logRecord.setSourceLineNumber(-1);
            }
        }
        if (properties != null) {
            logRecord.setMdc(properties);
        }
        logContext = null;
    }

    // our own constructors

    // This one is for testing only!
    public LoggingEvent(final ExtLogRecord logRecord, final Category logger) {
        this.logRecord = logRecord;
        fqnOfCategoryClass = logRecord.getLoggerClassName();
        this.logger = logger;
        level = JBossLevelMapping.getPriorityFor(logRecord.getLevel());
        categoryName = logRecord.getLoggerName();
        timeStamp = logRecord.getMillis();
        logContext = null;
    }

    public LoggingEvent(final ExtLogRecord logRecord, final LogContext logContext) {
        this.logRecord = logRecord;
        fqnOfCategoryClass = logRecord.getLoggerClassName();
        level = JBossLevelMapping.getPriorityFor(logRecord.getLevel());
        categoryName = logRecord.getLoggerName();
        timeStamp = logRecord.getMillis();
        this.logContext = logContext;
    }

    public LocationInfo getLocationInformation() {
        if (cachedLocationInfo == null) {
            cachedLocationInfo = new LocationInfo(logRecord.getSourceFileName(), logRecord.getSourceClassName(), logRecord.getSourceMethodName(), Integer.toString(logRecord.getSourceLineNumber()));
        }
        return cachedLocationInfo;
    }

    public Level getLevel() {
        return (Level) level;
    }

    public String getLoggerName() {
        return categoryName;
    }

    public Category getLogger() {
        Category logger = this.logger;
        if (logger == null) {
            // lazily populate
            assert logContext != null; // otherwise, our `logger` would be populated per constructor
            logger = this.logger = JBossLogManagerFacade.getLogger(logContext.getLogger(categoryName));
        }
        return logger;
    }

    public Object getMessage() {
        return logRecord.getMessage();
    }

    public String getNDC() {
        return logRecord.getNdc();
    }

    public Object getMDC(String key) {
        return logRecord.getMdc(key);
    }

    public void getMDCCopy() {
        logRecord.copyMdc();
    }

    public String getRenderedMessage() {
        return logRecord.getFormattedMessage();
    }

    public static long getStartTime() {
        return startTime;
    }

    public String getThreadName() {
        return logRecord.getThreadName();
    }

    public ThrowableInformation getThrowableInformation() {
        final Throwable thrown = logRecord.getThrown();
        if (cachedThrowableInformation == null && thrown != null) {
            cachedThrowableInformation = new ThrowableInformation(thrown, logger);
        }
        return cachedThrowableInformation;
    }

    public String[] getThrowableStrRep() {
        final ThrowableInformation cachedThrowableInformation = getThrowableInformation();
        return cachedThrowableInformation == null ? null : cachedThrowableInformation.getThrowableStrRep();
    }

    private void readObject(ObjectInputStream ois) throws java.io.IOException, ClassNotFoundException {
        final ObjectInputStream.GetField getField = ois.readFields();
        // read the level just like log4j does
        final int levelId = ois.readInt();
        final String levelClassName = (String) ois.readObject();
        Level level = null;
        if (levelClassName == null) {
            level = Level.toLevel(levelId);
        } else {
            final SecurityManager sm = System.getSecurityManager();
            final Method method;
            try {
                if (sm == null) {
                    @SuppressWarnings("unchecked")
                    final Class<? extends Level> levelClass = Loader.loadClass(levelClassName).asSubclass(Level.class);
                    method = levelClass.getDeclaredMethod("toLevel", int.class);
                } else {
                    method = AccessController.doPrivileged(new PrivilegedAction<Method>() {
                        @Override
                        public Method run() {
                            try {
                                @SuppressWarnings("unchecked")
                                final Class<? extends Level> levelClass = Loader.loadClass(levelClassName).asSubclass(Level.class);
                                return levelClass.getDeclaredMethod("toLevel", int.class);
                            } catch (NoSuchMethodException e) {
                                // Just rethrow and catch below
                                throw new RuntimeException(e);
                            } catch (ClassNotFoundException e) {
                                // Just rethrow and catch below
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }
                level = (Level) method.invoke(null, Integer.valueOf(levelId));
            } catch (Exception e) {
                // match the log4j message
                LogLog.warn("Level deserialization failed, reverting to default.", e);
                level = Level.toLevel(levelId);
            }
        }

        this.level = level;

        final String categoryName = (String) getField.get("categoryName", null);
        if (categoryName != null) {
            logger = Logger.getLogger(categoryName);
        }
        final Hashtable mdcCopy = (Hashtable) getField.get("mdcCopy", null);
        final String ndc = (String) getField.get("ndc", null);
        final String renderedMessage = (String) getField.get("renderedMessage", null);
        final long timeStamp = getField.get("timeStamp", -1L);
        final String threadName = (String) getField.get("threadName", null);
        final ThrowableInformation throwableInfo = (ThrowableInformation) getField.get("throwableInfo", ThrowableInformation.class);
        cachedThrowableInformation = throwableInfo;
        if (throwableInfo != null) {
            throwableInfo.getThrowableStrRep(); // force string representation to be cached
        }

        final ExtLogRecord record = new ExtLogRecord(JBossLevelMapping.getLevelFor(level), renderedMessage, ExtLogRecord.FormatStyle.NO_FORMAT, Logger.class.getName());
        if (categoryName != null) record.setLoggerName(categoryName);
        record.setMdc(mdcCopy == null ? Collections.<Object, Object>emptyMap() : mdcCopy);
        record.setNdc(ndc == null ? "" : ndc);
        record.setMillis(timeStamp);
        record.setThreadName(threadName == null ? "<unknown>" : threadName);
        record.setThrown(throwableInfo == null ? null : throwableInfo.getThrowable());
        record.disableCallerCalculation();
        cachedLocationInfo = new LocationInfo(null, null);
        try {
            fqnOfCategoryClassField.set(this, record.getLoggerClassName());
            logRecordField.set(this, record);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private void writeObject(ObjectOutputStream oos) throws java.io.IOException {
        final ObjectOutputStream.PutField putField = oos.putFields();
        putField.put("categoryName", logRecord.getLoggerName());
        putField.put("mdcCopy", new Hashtable<String, String>(logRecord.getMdcCopy()));
        putField.put("ndc", logRecord.getNdc());
        putField.put("renderedMessage", logRecord.getFormattedMessage());
        putField.put("timeStamp", logRecord.getMillis());
        putField.put("threadName", logRecord.getThreadName());
        getThrowableStrRep();
        putField.put("throwableInfo", getThrowableInformation());
        oos.writeFields();
        final Level level = getLevel();
        oos.writeInt(level.toInt());
        final Class<? extends Level> levelClass = level.getClass();
        oos.writeObject(levelClass == Level.class ? null : levelClass);
    }

    public final void setProperty(final String propName, final String propValue) {
        logRecord.putMdc(propName, propValue);
    }

    public final String getProperty(final String key) {
        return logRecord.getMdc(key);
    }

    public final boolean locationInformationExists() {
        return cachedLocationInfo != null;
    }

    public final long getTimeStamp() {
        return logRecord.getMillis();
    }

    public Set getPropertyKeySet() {
        return getProperties().keySet();
    }

    public Map getProperties() {
        return logRecord.getMdcCopy();
    }

    public String getFQNOfLoggerClass() {
        return fqnOfCategoryClass;
    }

    public Object removeProperty(String propName) {
        return logRecord.putMdc(propName, null);
    }

    public ExtLogRecord getLogRecord() {
        return logRecord;
    }

    private static Field getField(final String name) {
        return AccessController.doPrivileged(new PrivilegedAction<Field>() {
            public Field run() {
                final Field field;
                try {
                    field = LoggingEvent.class.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    throw new NoSuchFieldError(e.getMessage());
                }
                field.setAccessible(true);
                return field;
            }
        });
    }
}
