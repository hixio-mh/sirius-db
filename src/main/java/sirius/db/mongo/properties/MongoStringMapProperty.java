/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.db.mongo.properties;

import org.bson.Document;
import sirius.db.mixing.AccessPath;
import sirius.db.mixing.EntityDescriptor;
import sirius.db.mixing.Mixable;
import sirius.db.mixing.Mixing;
import sirius.db.mixing.Property;
import sirius.db.mixing.PropertyFactory;
import sirius.db.mixing.types.StringMap;
import sirius.db.mixing.properties.BaseMapProperty;
import sirius.db.mongo.MongoEntity;
import sirius.kernel.di.std.Register;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Represents an {@link StringMap} field within a {@link Mixable}.
 */
public class MongoStringMapProperty extends BaseMapProperty {

    /**
     * Factory for generating properties based on their field type
     */
    @Register
    public static class Factory implements PropertyFactory {

        @Override
        public boolean accepts(EntityDescriptor descriptor, Field field) {
            return MongoEntity.class.isAssignableFrom(descriptor.getType())
                   && StringMap.class.equals(field.getType());
        }

        @Override
        public void create(EntityDescriptor descriptor,
                           AccessPath accessPath,
                           Field field,
                           Consumer<Property> propertyConsumer) {
            if (!Modifier.isFinal(field.getModifiers())) {
                Mixing.LOG.WARN("Field %s in %s is not final! This will probably result in errors.",
                                field.getName(),
                                field.getDeclaringClass().getName());
            }

            propertyConsumer.accept(new MongoStringMapProperty(descriptor, accessPath, field));
        }
    }

    MongoStringMapProperty(EntityDescriptor descriptor, AccessPath accessPath, Field field) {
        super(descriptor, accessPath, field);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Object transformToDatasource(Object object) {
        if (object instanceof Document) {
            return object;
        }

        Document doc = new Document();
        doc.putAll((Map<String, String>) object);
        return doc;
    }
}
