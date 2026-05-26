package app.config;

import app.exceptions.ApiException;
import app.routes.*;
import app.security.enums.Role;
import app.security.routes.SecurityRoute;
import app.security.utils.JWTUtil;
import app.utils.ExecutionTimer;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplicationConfig
{
    public static Javalin startServer(int port)
    {
        ExecutionTimer.start();
        JWTUtil.validate();
        DIContainer diContainer = DIContainer.getInstance();
        DataSeeder.seed(diContainer);
        Routes routes = buildRoutes(diContainer);

        Javalin app = Javalin.create(config ->
        {
            configureRoutes(config, routes);
            configureSecurity(config, diContainer);
            configureCors(config);
            configureExceptions(config);
            configureJackson(config, diContainer);
            configureLogger(config);
        }).start(port);

        ExecutionTimer.finish("SheetHerder ready on port " + port);
        return app;
    }

    public static Javalin startServer(int port, EntityManagerFactory emf)
    {
        JWTUtil.validate();
        DIContainer diContainer = DIContainer.getTestInstance(emf);
        Routes routes = buildRoutes(diContainer);

        Javalin app = Javalin.create(config ->
        {
            configureRoutes(config, routes);
            configureSecurity(config, diContainer);
            configureCors(config);
            configureExceptions(config);
            configureJackson(config, diContainer);
            configureLogger(config);
        }).start(port);

        return app;
    }

    public static void stopServer(Javalin app)
    {
        log.info("SheetHerder shutting down");
        app.stop();
    }

    private static Routes buildRoutes(DIContainer diContainer)
    {
        return new Routes(
                new HealthCheckRoute(diContainer.getHealthCheckController()),
                new LanguageRoute(diContainer.getLanguageController()),
                new TraitRoute(diContainer.getTraitController()),
                new RaceRoute(diContainer.getRaceController()),
                new SubraceRoute(diContainer.getSubraceController()),
                new SecurityRoute(diContainer.getSecurityController()),
                new UserRoute(diContainer.getUserController()),
                new CharacterSheetRoute(diContainer.getCharacterSheetController())
        );
    }

    private static void configureRoutes(JavalinConfig config, Routes routes)
    {
        config.bundledPlugins.enableRouteOverview("/routes", Role.ANYONE);
        config.routes.apiBuilder(routes.getRoutes());
    }

    private static void configureSecurity(JavalinConfig config, DIContainer diContainer)
    {
        config.routes.beforeMatched(diContainer.getSecurityController()::authenticate);
        config.routes.beforeMatched(diContainer.getSecurityController()::authorize);
    }

    private static void configureCors(JavalinConfig config)
    {
        boolean isProduction = System.getenv("DEPLOYED") != null;

        config.bundledPlugins.enableCors(cors ->
        {
            cors.addRule(rule ->
            {
                if (isProduction)
                {
                    String allowedOrigins = System.getenv("CORS_ALLOWED_ORIGINS");
                    for (String origin : allowedOrigins.split(","))
                    {
                        rule.allowHost(origin.trim());
                    }
                }
                else
                {
                    rule.anyHost();
                }
            });
        });
    }

    private static void configureExceptions(JavalinConfig config)
    {
        config.routes.exception(ApiException.class, (e, ctx) ->
        {
            log.warn("{} {} - {}", ctx.method(), ctx.path(), e.getMessage());
            ctx.status(e.getCode())
                    .json(Map.of("status", e.getCode(),
                            "message", e.getMessage()));
        });

        config.routes.exception(NumberFormatException.class, (e, ctx) ->
        {
            log.warn("{} {} - Invalid number format: {}", ctx.method(), ctx.path(), e.getMessage());
            ctx.status(HttpStatus.BAD_REQUEST.getCode())
                    .json(Map.of("status", HttpStatus.BAD_REQUEST.getCode(),
                            "message", "Invalid ID format: expected a number"));
        });

        config.routes.exception(Exception.class, (e, ctx) ->
        {
            log.error("{} {} - Unhandled exception", ctx.method(), ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR.getCode())
                    .json(Map.of("status", HttpStatus.INTERNAL_SERVER_ERROR.getCode(),
                            "message", "Internal server error"));
        });
    }

    private static void configureLogger(JavalinConfig config)
    {
        config.requestLogger.http((ctx, ms) ->
        {
            if (ctx.path().equals("/api/v1/health-check"))
            {
                return;
            }
            if (ctx.status().getCode() >= 500)
            {
                log.error("{} {} - {} ({}ms)", ctx.method(), ctx.path(), ctx.status(), ms.longValue());
            }
            else if (ctx.status().getCode() >= 400)
            {
                log.warn("{} {} - {} ({}ms)", ctx.method(), ctx.path(), ctx.status(), ms.longValue());
            }
            else
            {
                log.info("{} {} - {} ({}ms)", ctx.method(), ctx.path(), ctx.status(), ms.longValue());
            }
        });
    }

    private static void configureJackson(JavalinConfig config, DIContainer diContainer)
    {
        config.jsonMapper(new JavalinJackson(diContainer.getObjectMapper(), false));
    }
}