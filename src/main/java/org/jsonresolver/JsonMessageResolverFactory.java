package org.jsonresolver;

import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolverContext;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolverFactory;
import org.apache.logging.log4j.layout.template.json.resolver.TemplateResolverConfig;
import org.apache.logging.log4j.layout.template.json.resolver.TemplateResolverFactory;

@Plugin(name = "JsonMessageResolverFactory", category = TemplateResolverFactory.CATEGORY)
public final class JsonMessageResolverFactory implements EventResolverFactory {

    private static final JsonMessageResolverFactory INSTANCE = new JsonMessageResolverFactory();

    private JsonMessageResolverFactory() {}

    @PluginFactory
    public static JsonMessageResolverFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return JsonMessageResolver.getName();
    }

    @Override
    public JsonMessageResolver create(EventResolverContext context, TemplateResolverConfig config) {
        return new JsonMessageResolver(config);
    }
}
