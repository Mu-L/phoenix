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
package org.apache.phoenix.parse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.jcip.annotations.Immutable;
import org.apache.phoenix.compile.ColumnResolver;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.expression.function.AggregateFunction;
import org.apache.phoenix.expression.function.FunctionExpression;
import org.apache.phoenix.expression.function.UDFExpression;
import org.apache.phoenix.parse.PFunction.FunctionArgument;
import org.apache.phoenix.schema.ArgumentTypeMismatchException;
import org.apache.phoenix.schema.FunctionNotFoundException;
import org.apache.phoenix.schema.ValueRangeExcpetion;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDataTypeFactory;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.util.SchemaUtil;

import org.apache.phoenix.thirdparty.com.google.common.collect.ImmutableSet;

/**
 * Node representing a function expression in SQL
 * @since 0.1
 */
public class FunctionParseNode extends CompoundParseNode {
  private final String name;
  private BuiltInFunctionInfo info;

  FunctionParseNode(String name, List<ParseNode> children, BuiltInFunctionInfo info) {
    super(children);
    this.name = SchemaUtil.normalizeIdentifier(name);
    this.info = info;
  }

  public BuiltInFunctionInfo getInfo() {
    return info;
  }

  public String getName() {
    return name;
  }

  @Override
  public <T> T accept(ParseNodeVisitor<T> visitor) throws SQLException {
    List<T> l = Collections.emptyList();
    if (visitor.visitEnter(this)) {
      l = acceptChildren(visitor);
    }
    return visitor.visitLeave(this, l);
  }

  public boolean isAggregate() {
    if (getInfo() == null) return false;
    return getInfo().isAggregate();
  }

  /**
   * Determines whether or not we can collapse a function expression to null if a required parameter
   * is null.
   * @param index index of parameter
   * @return true if when the parameter at index is null, the function always evaluates to null and
   *         false otherwise.
   */
  public boolean evalToNullIfParamIsNull(StatementContext context, int index) throws SQLException {
    return true;
  }

  private static Constructor<? extends FunctionParseNode>
    getParseNodeCtor(Class<? extends FunctionParseNode> clazz) {
    Constructor<? extends FunctionParseNode> ctor;
    try {
      ctor = clazz.getDeclaredConstructor(String.class, List.class, BuiltInFunctionInfo.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    ctor.setAccessible(true);
    return ctor;
  }

  private static Constructor<? extends FunctionExpression>
    getExpressionCtor(Class<? extends FunctionExpression> clazz, PFunction function) {
    Constructor<? extends FunctionExpression> ctor;
    try {
      if (function == null) {
        ctor = clazz.getDeclaredConstructor(List.class);
      } else {
        ctor = clazz.getDeclaredConstructor(List.class, PFunction.class);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    ctor.setAccessible(true);
    return ctor;
  }

  public List<Expression> validate(List<Expression> children, StatementContext context)
    throws SQLException {
    BuiltInFunctionInfo info = this.getInfo();
    BuiltInFunctionArgInfo[] args = info.getArgs();
    if (args.length < children.size() || info.getRequiredArgCount() > children.size()) {
      throw new FunctionNotFoundException(this.name);
    }
    if (args.length > children.size()) {
      List<Expression> moreChildren = new ArrayList<Expression>(children);
      for (int i = children.size(); i < info.getArgs().length; i++) {
        moreChildren.add(LiteralExpression.newConstant(null,
          args[i].allowedTypes.length == 0
            ? null
            : PDataTypeFactory.getInstance().instanceFromClass(args[i].allowedTypes[0]),
          Determinism.ALWAYS));
      }
      children = moreChildren;
    }
    List<ParseNode> nodeChildren = this.getChildren();
    for (int i = 0; i < children.size(); i++) {
      BindParseNode bindNode = null;
      Class<? extends PDataType>[] allowedTypes = args[i].getAllowedTypes();
      // Check if the node is a bind parameter, and set the parameter
      // metadata based on the function argument annotation. Check to
      // make sure we're not looking past the end of the list of
      // child expression nodes, which can happen if the function
      // invocation hasn't specified all arguments and is using default
      // values.
      if (i < nodeChildren.size() && nodeChildren.get(i) instanceof BindParseNode) {
        bindNode = (BindParseNode) nodeChildren.get(i);
      }
      // If the child type is null, then the expression is unbound.
      // Skip any validation, since we either 1) have a default value
      // which has already been validated, 2) attempting to get the
      // parameter metadata, or 3) have an unbound parameter which
      // will be detected futher downstream.
      Expression child = children.get(i);
      if (
        child.getDataType() == null
          /* null used explicitly in query */ || i >= nodeChildren.size() /* argument left off */
      ) {
        // Replace the unbound expression with the default value expression if specified
        if (args[i].getDefaultValue() != null) {
          Expression defaultValue = args[i].getDefaultValue();
          children.set(i, defaultValue);
          // Set the parameter metadata if this is a bind parameter
          if (bindNode != null) {
            context.getBindManager().addParamMetaData(bindNode, defaultValue);
          }
        } else if (bindNode != null) {
          // Otherwise if the node is a bind parameter and we have type information
          // based on the function argument annonation set the parameter meta data.
          if (child.getDataType() == null) {
            if (allowedTypes.length > 0) {
              context.getBindManager().addParamMetaData(bindNode,
                LiteralExpression.newConstant(null,
                  PDataTypeFactory.getInstance().instanceFromClass(allowedTypes[0]),
                  Determinism.ALWAYS));
            }
          } else { // Use expression as is, since we already have the data type set
            context.getBindManager().addParamMetaData(bindNode, child);
          }
        } else if (allowedTypes.length > 0) {
          // Switch null type with typed null
          children.set(i, LiteralExpression.newConstant(null,
            PDataTypeFactory.getInstance().instanceFromClass(allowedTypes[0]), Determinism.ALWAYS));
        }
      } else {
        validateFunctionArguement(info, i, child);
      }
    }
    return children;
  }

  public static void validateFunctionArguement(BuiltInFunctionInfo info, int childIndex,
    Expression child) throws ArgumentTypeMismatchException, ValueRangeExcpetion {
    BuiltInFunctionArgInfo arg = info.getArgs()[childIndex];
    if (arg.getAllowedTypes().length > 0) {
      boolean isCoercible = false;
      for (Class<? extends PDataType> type : arg.getAllowedTypes()) {
        if (
          child.getDataType().isCoercibleTo(PDataTypeFactory.getInstance().instanceFromClass(type))
        ) {
          isCoercible = true;
          break;
        }
      }
      if (!isCoercible) {
        throw new ArgumentTypeMismatchException(arg.getAllowedTypes(), child.getDataType(),
          info.getName() + " argument " + (childIndex + 1));
      }
      if (child instanceof LiteralExpression) {
        LiteralExpression valueExp = (LiteralExpression) child;
        LiteralExpression minValue = arg.getMinValue();
        LiteralExpression maxValue = arg.getMaxValue();
        if (
          minValue != null && minValue.getDataType().compareTo(minValue.getValue(),
            valueExp.getValue(), valueExp.getDataType()) > 0
        ) {
          throw new ValueRangeExcpetion(minValue, maxValue == null ? "" : maxValue,
            valueExp.getValue(), info.getName() + " argument " + (childIndex + 1));
        }
        if (
          maxValue != null && maxValue.getDataType().compareTo(maxValue.getValue(),
            valueExp.getValue(), valueExp.getDataType()) < 0
        ) {
          throw new ValueRangeExcpetion(minValue == null ? "" : minValue, maxValue,
            valueExp.getValue(), info.getName() + " argument " + (childIndex + 1));
        }
      }
    }
    if (arg.isConstant() && !(child instanceof LiteralExpression)) {
      throw new ArgumentTypeMismatchException("constant", child.toString(),
        info.getName() + " argument " + (childIndex + 1));
    }
    if (!arg.getAllowedValues().isEmpty()) {
      Object value = ((LiteralExpression) child).getValue();
      if (!arg.getAllowedValues().contains(value.toString().toUpperCase())) {
        throw new ArgumentTypeMismatchException(
          Arrays.toString(arg.getAllowedValues().toArray(new String[0])), value.toString(),
          info.getName() + " argument " + (childIndex + 1));
      }
    }
  }

  /**
   * Entry point for parser to instantiate compiled representation of built-in function
   * @param children Compiled expressions for child nodes
   * @param context  Query context for accessing state shared across the processing of multiple
   *                 clauses
   * @return compiled representation of built-in function
   */
  public Expression create(List<Expression> children, StatementContext context)
    throws SQLException {
    return create(children, null, context);
  }

  /**
   * Entry point for parser to instantiate compiled representation of built-in function
   * @param children Compiled expressions for child nodes
   * @param context  Query context for accessing state shared across the processing of multiple
   *                 clauses
   * @return compiled representation of built-in function
   */
  public Expression create(List<Expression> children, PFunction function, StatementContext context)
    throws SQLException {
    try {
      Constructor<? extends FunctionExpression> fCtor = info.getFuncCtor();
      if (fCtor == null) {
        fCtor = getExpressionCtor(info.func, null);
      }
      if (function == null) {
        return fCtor.newInstance(children);
      } else {
        return fCtor.newInstance(children, function);
      }
    } catch (InstantiationException e) {
      throw new SQLException(e);
    } catch (IllegalAccessException e) {
      throw new SQLException(e);
    } catch (IllegalArgumentException e) {
      throw new SQLException(e);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof SQLException) {
        throw (SQLException) e.getTargetException();
      }
      throw new SQLException(e);
    }
  }

  /**
   * Marker used to indicate that a class should be used by DirectFunctionExpressionExec below
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface BuiltInFunction {
    String name();

    Argument[] args() default {};

    Class<? extends FunctionParseNode> nodeClass() default FunctionParseNode.class;

    Class<? extends FunctionExpression>[] derivedFunctions() default {};

    FunctionClassType classType() default FunctionClassType.NONE;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface Argument {
    Class<? extends PDataType>[] allowedTypes() default {};

    boolean isConstant() default false;

    String defaultValue() default "";

    String enumeration() default "";

    String minValue() default "";

    String maxValue() default "";
  }

  public enum FunctionClassType {
    NONE,
    ABSTRACT,
    DERIVED,
    ALIAS,
    UDF
  }

  /**
   * Structure used to hold parse-time information about Function implementation classes
   */
  @Immutable
  public static final class BuiltInFunctionInfo {
    private final String name;
    private final Class<? extends FunctionExpression> func;
    private final Constructor<? extends FunctionExpression> funcCtor;
    private final Constructor<? extends FunctionParseNode> nodeCtor;
    private final BuiltInFunctionArgInfo[] args;
    private final boolean isAggregate;
    private final int requiredArgCount;
    private final FunctionClassType classType;
    private final Class<? extends FunctionExpression>[] derivedFunctions;

    public BuiltInFunctionInfo(Class<? extends FunctionExpression> f, BuiltInFunction d) {
      this.name = SchemaUtil.normalizeIdentifier(d.name());
      this.func = f;
      this.funcCtor = d.nodeClass() == FunctionParseNode.class ? getExpressionCtor(f, null) : null;
      this.nodeCtor =
        d.nodeClass() == FunctionParseNode.class ? null : getParseNodeCtor(d.nodeClass());
      this.args = new BuiltInFunctionArgInfo[d.args().length];
      int requiredArgCount = 0;
      for (int i = 0; i < args.length; i++) {
        this.args[i] = new BuiltInFunctionArgInfo(d.args()[i]);
        if (this.args[i].getDefaultValue() == null) {
          requiredArgCount = i + 1;
        }
      }
      this.requiredArgCount = requiredArgCount;
      this.isAggregate = AggregateFunction.class.isAssignableFrom(f);
      this.classType = d.classType();
      this.derivedFunctions = d.derivedFunctions();
    }

    public BuiltInFunctionInfo(PFunction function) {
      this.name = SchemaUtil.normalizeIdentifier(function.getFunctionName());
      this.func = null;
      this.funcCtor = getExpressionCtor(UDFExpression.class, function);
      this.nodeCtor = getParseNodeCtor(UDFParseNode.class);
      this.args = new BuiltInFunctionArgInfo[function.getFunctionArguments().size()];
      int requiredArgCount = 0;
      for (int i = 0; i < args.length; i++) {
        this.args[i] = new BuiltInFunctionArgInfo(function.getFunctionArguments().get(i));
        if (this.args[i].getDefaultValue() == null) {
          requiredArgCount = i + 1;
        }
      }
      this.requiredArgCount = requiredArgCount;
      this.isAggregate = AggregateFunction.class.isAssignableFrom(UDFExpression.class);
      this.classType = FunctionClassType.UDF;
      this.derivedFunctions = null;
    }

    public int getRequiredArgCount() {
      return requiredArgCount;
    }

    public String getName() {
      return name;
    }

    public Class<? extends FunctionExpression> getFunc() {
      return func;
    }

    public Constructor<? extends FunctionExpression> getFuncCtor() {
      return funcCtor;
    }

    public Constructor<? extends FunctionParseNode> getNodeCtor() {
      return nodeCtor;
    }

    public boolean isAggregate() {
      return isAggregate;
    }

    public BuiltInFunctionArgInfo[] getArgs() {
      return args;
    }

    public FunctionClassType getClassType() {
      return classType;
    }

    public Class<? extends FunctionExpression>[] getDerivedFunctions() {
      return derivedFunctions;
    }
  }

  @Immutable
  public static class BuiltInFunctionArgInfo {
    private static final Class<? extends PDataType>[] ENUMERATION_TYPES =
      new Class[] { PVarchar.class };
    private final Class<? extends PDataType>[] allowedTypes;
    private final boolean isConstant;
    private final Set<String> allowedValues; // Enumeration of possible values
    private final LiteralExpression defaultValue;
    private final LiteralExpression minValue;
    private final LiteralExpression maxValue;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    BuiltInFunctionArgInfo(Argument argument) {

      if (argument.enumeration().length() > 0) {
        this.isConstant = true;
        this.defaultValue = null;
        this.minValue = null;
        this.maxValue = null;
        this.allowedTypes = ENUMERATION_TYPES;
        Class<?> clazz = null;
        String packageName = FunctionExpression.class.getPackage().getName();
        try {
          clazz = Class.forName(packageName + "." + argument.enumeration());
        } catch (ClassNotFoundException e) {
          try {
            clazz = Class.forName(argument.enumeration());
          } catch (ClassNotFoundException e1) {
          }
        }
        if (clazz == null || !clazz.isEnum()) {
          throw new IllegalStateException("The enumeration annotation '" + argument.enumeration()
            + "' does not resolve to a enumeration class");
        }
        Class<? extends Enum> enumClass = (Class<? extends Enum>) clazz;
        Enum[] enums = enumClass.getEnumConstants();
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (Enum en : enums) {
          builder.add(en.name());
        }
        allowedValues = builder.build();
      } else {
        this.allowedValues = Collections.emptySet();
        this.isConstant = argument.isConstant();
        this.allowedTypes = argument.allowedTypes();
        this.defaultValue = getExpFromConstant(argument.defaultValue());
        this.minValue = getExpFromConstant(argument.minValue());
        this.maxValue = getExpFromConstant(argument.maxValue());
      }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    BuiltInFunctionArgInfo(FunctionArgument arg) {
      PDataType dataType = arg.isArrayType()
        ? PDataType.fromTypeId(PDataType.sqlArrayType(
          SchemaUtil.normalizeIdentifier(SchemaUtil.normalizeIdentifier(arg.getArgumentType()))))
        : PDataType.fromSqlTypeName(SchemaUtil.normalizeIdentifier(arg.getArgumentType()));
      this.allowedValues = Collections.emptySet();
      this.allowedTypes = new Class[] { dataType.getClass() };
      this.isConstant = arg.isConstant();
      this.defaultValue = arg.getDefaultValue() == null ? null : arg.getDefaultValue();
      this.minValue = arg.getMinValue() == null ? null : arg.getMinValue();
      this.maxValue = arg.getMaxValue() == null ? null : arg.getMaxValue();
    }

    private LiteralExpression getExpFromConstant(String strValue) {
      LiteralExpression exp = null;
      if (strValue.length() > 0) {
        SQLParser parser = new SQLParser(strValue);
        try {
          LiteralParseNode node = parser.parseLiteral();
          LiteralExpression defaultValue = LiteralExpression.newConstant(node.getValue(),
            PDataTypeFactory.getInstance().instanceFromClass(allowedTypes[0]), Determinism.ALWAYS);
          if (this.getAllowedTypes().length > 0) {
            for (Class<? extends PDataType> type : this.getAllowedTypes()) {
              if (
                defaultValue.getDataType() == null || defaultValue.getDataType().isCoercibleTo(
                  PDataTypeFactory.getInstance().instanceFromClass(type), node.getValue())
              ) {
                return LiteralExpression.newConstant(node.getValue(),
                  PDataTypeFactory.getInstance().instanceFromClass(type), Determinism.ALWAYS);
              }
            }
            throw new IllegalStateException("Unable to coerce default value " + strValue
              + " to any of the allowed types of " + Arrays.toString(this.getAllowedTypes()));
          }
          exp = defaultValue;
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }
      return exp;
    }

    public boolean isConstant() {
      return isConstant;
    }

    public LiteralExpression getDefaultValue() {
      return defaultValue;
    }

    public LiteralExpression getMinValue() {
      return minValue;
    }

    public LiteralExpression getMaxValue() {
      return maxValue;
    }

    public Class<? extends PDataType>[] getAllowedTypes() {
      return allowedTypes;
    }

    public Set<String> getAllowedValues() {
      return allowedValues;
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + ((info == null) ? 0 : info.hashCode());
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!super.equals(obj)) return false;
    if (getClass() != obj.getClass()) return false;
    FunctionParseNode other = (FunctionParseNode) obj;
    if (info == null) {
      if (other.info != null) return false;
    } else if (!info.equals(other.info)) return false;
    if (name == null) {
      if (other.name != null) return false;
    } else if (!name.equals(other.name)) return false;
    return true;
  }

  @Override
  public void toSQL(ColumnResolver resolver, StringBuilder buf) {
    buf.append(' ');
    buf.append(name);
    buf.append('(');
    List<ParseNode> children = getChildren();
    if (!children.isEmpty()) {
      for (ParseNode child : children) {
        child.toSQL(resolver, buf);
        buf.append(',');
      }
      buf.setLength(buf.length() - 1);
    }
    buf.append(')');
  }
}
