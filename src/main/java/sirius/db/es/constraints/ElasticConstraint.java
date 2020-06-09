/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.db.es.constraints;

import com.alibaba.fastjson.JSONObject;
import sirius.db.es.Elastic;
import sirius.db.mixing.query.constraints.Constraint;
import sirius.kernel.commons.Explain;

/**
 * Defines a constraint which is accepted by {@link sirius.db.es.ElasticQuery} and most probably generated by
 * {@link ElasticFilterFactory}.
 *
 * @see sirius.db.es.Elastic#FILTERS
 */
public class ElasticConstraint extends Constraint {

    private JSONObject constraint;

    /**
     * Creates a new constraint represented as JSON.
     *
     * @param constraint the JSON making up the constraint
     */
    @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
    @Explain("Deep copy of a JSON object is too expensive here as it is mostly an internal API")
    public ElasticConstraint(JSONObject constraint) {
        this.constraint = constraint;
    }

    /**
     * Makes this constraint a named query.
     *
     * @param name the name of the query
     * @return the constraint itself for fluent method calls
     */
    public ElasticConstraint named(String name) {
        this.constraint.put("_name", name);

        return this;
    }

    @Override
    public void asString(StringBuilder builder) {
        builder.append(constraint);
    }

    /**
     * Returns the JSON representation of this constraint.
     *
     * @return the JSON to send to the Elasticsearch server for this constraint
     */
    @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
    @Explain("Deep copy of a JSON object is too expensive here as it is mostly an internal API")
    public JSONObject toJSON() {
        return constraint;
    }

    /**
     * Wraps the given constraint using {@link ElasticFilterFactory#constantScore(ElasticConstraint, float)}.
     * <p>
     * Note that a constraint which influences the scoring must be added using
     * {@link sirius.db.es.ElasticQuery#must(ElasticConstraint)}.
     *
     * @param boost the boost or constant score to apply to this constraint
     * @return the newly created constraint
     */
    public ElasticConstraint withConstantScore(float boost) {
        return Elastic.FILTERS.constantScore(this, boost);
    }
}
