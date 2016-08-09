/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

/**
 * Thin layer above Redis (using Jedis)
 * <p>
 * Operations against redis are encapsulated in <tt>Redis.query</tt> and <tt>Redis.exec</tt>.
 * <p>
 * One to many communication is supported by implementing {@link sirius.db.redis.Subscriber} and invoking
 * <tt>Redis.publish</tt>.
 * <p>
 * Setup is done via the system configuration most probably by just providing a value for <tt>redis.host</tt>.
 *
 * @see sirius.db.redis.Redis#query(java.util.function.Supplier, java.util.function.Function)
 * @see sirius.db.redis.Redis#exec(java.util.function.Supplier, java.util.function.Consumer)
 * @see sirius.db.redis.Redis#publish(java.lang.String, java.lang.String)
 */
package sirius.db.redis;