/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.iterate;

import java.sql.SQLException;
import java.util.List;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.compile.ExplainPlanAttributes.ExplainPlanAttributesBuilder;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.aggregator.Aggregator;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PBoolean;

/**
 * Post aggregation filter for HAVING clause. Due to the way we cache aggregation values we cannot
 * have a look ahead for this Iterator, because the expressions in the SELECT clause would return
 * values for the peeked row instead of the current row. If we only use the Result argument in
 * {@link org.apache.phoenix.expression.Expression} instead of our cached value in Aggregators, we
 * could have a look ahead.
 * @since 0.1
 */
public class FilterAggregatingResultIterator implements AggregatingResultIterator {
  private final AggregatingResultIterator delegate;
  private final Expression expression;
  private final ImmutableBytesWritable ptr = new ImmutableBytesWritable();

  public FilterAggregatingResultIterator(AggregatingResultIterator delegate,
    Expression expression) {
    this.delegate = delegate;
    this.expression = expression;
    if (expression.getDataType() != PBoolean.INSTANCE) {
      throw new IllegalArgumentException(
        "FilterResultIterator requires a boolean expression, but got " + expression);
    }
  }

  @Override
  public Tuple next() throws SQLException {
    Tuple next;
    do {
      next = delegate.next();
    } while (
      next != null && expression.evaluate(next, ptr)
        && Boolean.FALSE.equals(expression.getDataType().toObject(ptr))
    );
    return next;
  }

  @Override
  public void close() throws SQLException {
    delegate.close();
  }

  @Override
  public Aggregator[] aggregate(Tuple result) {
    return delegate.aggregate(result);
  }

  @Override
  public void explain(List<String> planSteps) {
    delegate.explain(planSteps);
    planSteps.add("CLIENT FILTER BY " + expression.toString());
  }

  @Override
  public void explain(List<String> planSteps,
    ExplainPlanAttributesBuilder explainPlanAttributesBuilder) {
    delegate.explain(planSteps, explainPlanAttributesBuilder);
    explainPlanAttributesBuilder.setClientFilterBy(expression.toString());
    planSteps.add("CLIENT FILTER BY " + expression.toString());
  }

  @Override
  public String toString() {
    return "FilterAggregatingResultIterator [delegate=" + delegate + ", expression=" + expression
      + ", ptr=" + ptr + "]";
  }
}
