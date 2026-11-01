package ru.cherdakmd.chrdkregister;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the arguments of authentication commands in the server log.
 *
 * <p>Spigot/Paper logs every player command as
 * {@code <name> issued server command: /login secret} ({@code spigot.yml -> commands.log}). This class
 * installs a log4j2 {@code Filter} through reflection (Paper ships log4j2, but the plugin is compiled only
 * against the API), rewriting the arguments of {@code /login}, {@code /register}, {@code /changepassword} and
 * {@code /2fa} to {@code ********}. When log4j2 is unavailable or the API changes, masking silently
 * degrades: the plugin keeps working and the logger just records a warning.</p>
 */
public final class LogMasker {
    private static final String FILTER_NAME = "CHRDK-auth-mask";
    private static final Pattern AUTH_COMMAND = Pattern.compile(
            "(?iu)(issued server command:\\s*|command:\\s*)?/((?:[a-z0-9_]+:)?(?:login|register|reg|changepassword|changepass|cp|2fa|totp|twofactor|vk|vkontakte|вк))(?=\\s|$)(.*)");

    private final Logger logger;
    private Object rootLogger;
    private Object filter;

    public LogMasker(Logger logger) {
        this.logger = logger;
    }

    /** Installs the masking filter; safe to call repeatedly. */
    public synchronized void install() {
        if (filter != null) return;
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> filterClass = Class.forName("org.apache.logging.log4j.core.Filter");
            Object candidate = Proxy.newProxyInstance(
                    filterClass.getClassLoader(),
                    new Class<?>[]{filterClass},
                    new MaskHandler());
            Object loggerInstance = logManagerClass.getMethod("getRootLogger").invoke(null);
            Method addFilter = loggerInstance.getClass().getMethod("addFilter", filterClass);
            addFilter.invoke(loggerInstance, candidate);
            this.rootLogger = loggerInstance;
            this.filter = candidate;
            logger.info("Command log masking enabled: /login, /register, /changepassword and /2fa "
                    + "arguments are hidden in the server log.");
        } catch (Throwable throwable) {
            logger.log(Level.FINE, "log4j2 log masking is unavailable on this server", throwable);
            this.filter = null;
            this.rootLogger = null;
        }
    }

    /** Removes the masking filter when it is no longer needed (configuration reload). */
    public synchronized void uninstall() {
        if (filter == null || rootLogger == null) return;
        try {
            Class<?> filterClass = Class.forName("org.apache.logging.log4j.core.Filter");
            Method removeFilter = rootLogger.getClass().getMethod("removeFilter", filterClass);
            removeFilter.invoke(rootLogger, filter);
        } catch (Throwable throwable) {
            logger.log(Level.FINE, "Could not remove the log masking filter", throwable);
        } finally {
            filter = null;
            rootLogger = null;
        }
    }

    /** Returns {@code true} when masking is currently active. */
    public synchronized boolean installed() {
        return filter != null;
    }

    /** Masks the arguments of auth commands inside a raw log line. */
    public static String mask(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) return rawLine;
        String lowered = rawLine.toLowerCase(Locale.ROOT);
        if (!lowered.contains("issued server command:") && !lowered.contains("command:")) {
            return rawLine;
        }
        Matcher matcher = AUTH_COMMAND.matcher(rawLine);
        if (!matcher.find()) return rawLine;
        String arguments = matcher.group(3) == null ? "" : matcher.group(3);
        if (arguments.isBlank()) return rawLine;
        return rawLine.substring(0, matcher.start(3)) + " ********";
    }

    /** log4j2 filter implementation that denies nothing but masks the message before it is written. */
    private static final class MaskHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "filter" -> filterEvent(args);
                case "getName", "name" -> FILTER_NAME;
                case "isStarted" -> Boolean.TRUE;
                case "isStopped" -> Boolean.FALSE;
                case "getState" -> stateValue();
                case "toString" -> FILTER_NAME;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }

        private Object filterEvent(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) return null;
            try {
                Object event = args[0];
                Object message = event.getClass().getMethod("getMessage").invoke(event);
                if (message == null) return null;
                String formatted = (String) message.getClass().getMethod("getFormattedMessage").invoke(message);
                String masked = mask(formatted);
                if (masked == null || masked.equals(formatted)) return null;
                // Patch the message in place so the log line keeps its level, time and logger name.
                Object newMessage = Class.forName("org.apache.logging.log4j.message.SimpleMessage")
                        .getConstructor(String.class)
                        .newInstance(masked);
                Method setMessage = findSetMessage(event.getClass());
                if (setMessage != null) {
                    setMessage.invoke(event, newMessage);
                }
            } catch (Throwable ignored) {
                // Never break logging because of masking.
            }
            return null;
        }

        private Method findSetMessage(Class<?> eventClass) {
            Class<?> current = eventClass;
            while (current != null) {
                try {
                    return current.getMethod("setMessage", Class.forName("org.apache.logging.log4j.message.Message"));
                } catch (Throwable ignored) {
                    current = current.getSuperclass();
                }
            }
            return null;
        }

        private Object stateValue() {
            try {
                Class<?> stateClass = Class.forName("org.apache.logging.log4j.core.LifeCycle$State");
                return stateClass.getField("INSTANCE").get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
