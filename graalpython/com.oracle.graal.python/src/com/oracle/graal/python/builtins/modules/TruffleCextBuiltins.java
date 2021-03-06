/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 *     one is included with the Software (each a "Larger Work" to which the
 *     Software is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.modules;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.modules.TruffleCextBuiltinsFactory.PNativeToPTypeNodeGen;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.bytes.BytesBuiltins;
import com.oracle.graal.python.builtins.objects.bytes.PBytes;
import com.oracle.graal.python.builtins.objects.cext.CExtNodes;
import com.oracle.graal.python.builtins.objects.cext.NativeCAPISymbols;
import com.oracle.graal.python.builtins.objects.cext.PythonObjectNativeWrapper;
import com.oracle.graal.python.builtins.objects.cext.UnicodeObjectNodes.UnicodeAsWideCharNode;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.exception.PBaseException;
import com.oracle.graal.python.builtins.objects.floats.PFloat;
import com.oracle.graal.python.builtins.objects.function.Arity;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.str.PString;
import com.oracle.graal.python.builtins.objects.traceback.PTraceback;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.builtins.objects.type.PythonClass;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.SpecialAttributeNames;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.attributes.WriteAttributeToObjectNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallUnaryNode;
import com.oracle.graal.python.nodes.expression.BinaryComparisonNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonTernaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.interop.PForeignToPTypeNode;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonCore;
import com.oracle.graal.python.runtime.exception.ExceptionUtils;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.exception.PythonErrorType;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

@CoreFunctions(defineModule = "python_cext")
public class TruffleCextBuiltins extends PythonBuiltins {

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinNode>> getNodeFactories() {
        return TruffleCextBuiltinsFactory.getFactories();
    }

    /**
     * Called mostly from our C code to convert arguments into a wrapped representation for
     * consumption in Java.
     */
    @Builtin(name = "to_java", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    public abstract static class AsPythonObjectNode extends PythonUnaryBuiltinNode {
        @Specialization
        Object run(Object object,
                        @Cached("create()") CExtNodes.AsPythonObjectNode toJavaNode) {
            return toJavaNode.execute(object);
        }
    }

    @Builtin(name = "is_python_object", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class IsPythonObjectNode extends PythonBuiltinNode {
        public abstract boolean execute(Object value);

        @Specialization
        boolean run(Object value) {
            return value instanceof PythonAbstractObject;
        }
    }

    /**
     * Called from C when they actually want a {@code const char*} for a Python string
     */
    @Builtin(name = "to_char_pointer", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class TruffleString_AsString extends NativeBuiltin {
        @CompilationFinal TruffleObject truffle_string_to_cstr;
        @Child private Node executeNode;

        TruffleObject getTruffleStringToCstr() {
            if (truffle_string_to_cstr == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                truffle_string_to_cstr = (TruffleObject) getContext().getEnv().importSymbol(NativeCAPISymbols.FUN_PY_TRUFFLE_STRING_TO_CSTR);
            }
            return truffle_string_to_cstr;
        }

        private Node getExecuteNode() {
            if (executeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                executeNode = insert(Message.createExecute(1).createNode());
            }
            return executeNode;
        }

        @Specialization
        Object run(PString str) {
            return run(str.getValue());
        }

        @Specialization
        Object run(String str) {
            try {
                return ForeignAccess.sendExecute(getExecuteNode(), getTruffleStringToCstr(), str);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw e.raise();
            }
        }

        @Fallback
        Object run(Object o) {
            return raiseNative(PNone.NO_VALUE, PythonErrorType.SystemError, "Cannot convert object of type %p to C string.", o);
        }

    }

    /**
     * This is used in the ExternalFunctionNode below, so all arguments passed from Python code into
     * a C function are automatically unwrapped if they are wrapped. This function is also called
     * all over the place in C code to make sure return values have the right representation in
     * Sulong land.
     */
    @Builtin(name = "to_sulong", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    public abstract static class ToSulongNode extends PythonUnaryBuiltinNode {

        @Specialization
        Object run(Object obj,
                        @Cached("create()") CExtNodes.ToSulongNode toSulongNode) {
            return toSulongNode.execute(obj);
        }
    }

    @Builtin(name = "to_long", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class AsLong extends PythonBuiltinNode {
        @Child private BuiltinConstructors.IntNode intNode;

        abstract Object executeWith(Object value);

        @Specialization
        int run(boolean value) {
            return value ? 1 : 0;
        }

        @Specialization
        int run(int value) {
            return value;
        }

        @Specialization
        long run(long value) {
            return value;
        }

        @Specialization
        long run(double value) {
            return (long) value;
        }

        @Specialization
        @TruffleBoundary
        long run(PInt value) {
            return value.getValue().longValue();
        }

        @Specialization
        long run(PFloat value) {
            return (long) value.getValue();
        }

        @Specialization
        Object run(PythonObjectNativeWrapper value,
                        @Cached("create()") AsLong recursive) {
            return recursive.executeWith(value.getPythonObject());
        }

        private BuiltinConstructors.IntNode getIntNode() {
            if (intNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                intNode = BuiltinConstructorsFactory.IntNodeFactory.create(null);
            }
            return intNode;
        }

        @Fallback
        Object runGeneric(Object value) {
            return getIntNode().executeWith(getCore().lookupType(Integer.class), value, PNone.NONE);
        }

        static AsLong create() {
            return TruffleCextBuiltinsFactory.AsLongFactory.create(null);
        }
    }

    @Builtin(name = "to_double", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class AsDouble extends PythonBuiltinNode {
        @Child private LookupAndCallUnaryNode callFloatFunc;

        @Specialization
        double run(boolean value) {
            return value ? 1.0 : 0.0;
        }

        @Specialization
        double run(int value) {
            return value;
        }

        @Specialization
        double run(long value) {
            return value;
        }

        @Specialization
        double run(double value) {
            return value;
        }

        @Specialization
        @TruffleBoundary
        double run(PInt value) {
            return value.getValue().doubleValue();
        }

        @Specialization
        double run(PFloat value) {
            return value.getValue();
        }

        // TODO: this should just use the builtin constructor node so we don't duplicate the corner
        // cases
        @Fallback
        double runGeneric(Object value) {
            if (callFloatFunc == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callFloatFunc = insert(LookupAndCallUnaryNode.create(SpecialMethodNames.__FLOAT__));
            }
            Object result = callFloatFunc.executeObject(value);
            if (PGuards.isPFloat(result)) {
                return ((PFloat) result).getValue();
            } else if (result instanceof Double) {
                return (double) result;
            } else {
                throw raise(PythonErrorType.TypeError, "%p.%s returned non-float (type %p)", value, SpecialMethodNames.__FLOAT__, result);
            }
        }
    }

    @Builtin(name = "PyTuple_SetItem", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class PyTuple_SetItem extends NativeBuiltin {
        @Specialization
        int doI(PTuple tuple, int position, Object element) {
            Object[] store = tuple.getArray();
            if (position < 0 || position >= store.length) {
                return raiseNative(-1, PythonErrorType.IndexError, "tuple assignment index out of range");
            }
            store[position] = element;
            return 0;
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        int doL(PTuple tuple, long position, Object element) {
            return doI(tuple, PInt.intValueExact(position), element);
        }

        @Specialization
        int doLOvf(PTuple tuple, long position, Object element) {
            try {
                return doI(tuple, PInt.intValueExact(position), element);
            } catch (ArithmeticException e) {
                return raiseNative(-1, PythonErrorType.IndexError, "cannot fit 'int' into an index-sized integer");
            }
        }
    }

    @Builtin(name = "CreateFunction", minNumOfArguments = 2, maxNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class CreateFunctionNode extends PythonTernaryBuiltinNode {
        @Specialization(guards = "isNoValue(cwrapper)")
        @TruffleBoundary
        Object runWithoutCWrapper(String name, TruffleObject callable, @SuppressWarnings("unused") PNone cwrapper) {
            RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(new ExternalFunctionNode(getRootNode().getLanguage(PythonLanguage.class), name, null, callable));
            return factory().createBuiltinFunction(name, createArity(name), callTarget);
        }

        @Specialization(guards = "!isNoValue(cwrapper)")
        @TruffleBoundary
        Object run(String name, TruffleObject callable, TruffleObject cwrapper) {
            RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(new ExternalFunctionNode(getRootNode().getLanguage(PythonLanguage.class), name, cwrapper, callable));
            return factory().createBuiltinFunction(name, createArity(name), callTarget);
        }

        private static Arity createArity(String name) {
            return new Arity(name, 0, 0, true, true, new ArrayList<>(), new ArrayList<>());
        }

        @Specialization(guards = "!isNoValue(cwrapper)")
        Object runWithoutCWrapper(PString name, TruffleObject callable, PNone cwrapper) {
            return runWithoutCWrapper(name.getValue(), callable, cwrapper);
        }

        @Specialization(guards = "!isNoValue(cwrapper)")
        Object run(PString name, TruffleObject callable, TruffleObject cwrapper) {
            return run(name.getValue(), callable, cwrapper);
        }
    }

    @Builtin(name = "PyErr_Restore", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class PyErrRestoreNode extends PythonBuiltinNode {
        @Specialization
        @SuppressWarnings("unused")
        Object run(PNone typ, PNone val, PNone tb) {
            getContext().setCurrentException(null);
            return PNone.NONE;
        }

        @Specialization
        Object run(@SuppressWarnings("unused") PythonClass typ, PBaseException val, @SuppressWarnings("unused") PTraceback tb) {
            val.reifyException();
            if (val.getException() != null) {
                getContext().setCurrentException(val.getException());
            } else {
                PException pException = new PException(val, this);
                val.setException(pException);
                getContext().setCurrentException(pException);
            }
            return PNone.NONE;
        }
    }

    @Builtin(name = "PyErr_Occurred", fixedNumOfArguments = 0)
    @GenerateNodeFactory
    abstract static class PyErrOccurred extends PythonBuiltinNode {
        @Specialization
        Object run() {
            PException currentException = getContext().getCurrentException();
            if (currentException != null) {
                currentException.getExceptionObject().reifyException();
                return currentException.getType();
            }
            return PNone.NO_VALUE;
        }
    }

    /**
     * Exceptions are usually printed using the traceback module or the hook function
     * {@code sys.excepthook}. This is the last resort if the hook function itself failed.
     */
    @Builtin(name = "PyErr_Display", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class PyErrDisplay extends PythonBuiltinNode {

        @Specialization
        @SuppressWarnings("unused")
        Object run(PythonClass typ, PBaseException val, PTraceback tb) {
            if (val.getException() != null) {
                ExceptionUtils.printPythonLikeStackTrace(val.getException());
            }
            return PNone.NO_VALUE;
        }
    }

    @Builtin(name = "PyUnicode_FromString", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class PyUnicodeFromStringNode extends PythonBuiltinNode {
        @Specialization
        PString run(String str) {
            return factory().createString(str);
        }

        @Specialization
        PString run(PString str) {
            return str;
        }
    }

    @Builtin(name = "do_richcompare", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class RichCompareNode extends PythonBuiltinNode {
        private static final String[] opstrings = new String[]{"<", "<=", "==", "!=", ">", ">="};
        private static final String[] opnames = new String[]{
                        SpecialMethodNames.__LT__, SpecialMethodNames.__LE__, SpecialMethodNames.__EQ__, SpecialMethodNames.__NE__, SpecialMethodNames.__GT__, SpecialMethodNames.__GE__};
        private static final String[] reversals = new String[]{
                        SpecialMethodNames.__GT__, SpecialMethodNames.__GE__, SpecialMethodNames.__EQ__, SpecialMethodNames.__NE__, SpecialMethodNames.__GT__, SpecialMethodNames.__GE__};

        protected static BinaryComparisonNode create(int op) {
            return BinaryComparisonNode.create(opnames[op], reversals[op], opstrings[op]);
        }

        @Specialization(guards = "op == 0")
        boolean op0(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }

        @Specialization(guards = "op == 1")
        boolean op1(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }

        @Specialization(guards = "op == 2")
        boolean op2(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }

        @Specialization(guards = "op == 3")
        boolean op3(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }

        @Specialization(guards = "op == 4")
        boolean op4(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }

        @Specialization(guards = "op == 5")
        boolean op5(Object a, Object b, @SuppressWarnings("unused") int op,
                        @Cached("create(op)") BinaryComparisonNode compNode) {
            return compNode.executeBool(a, b);
        }
    }

    @Builtin(name = "PyType_Ready", fixedNumOfArguments = 5)
    @GenerateNodeFactory
    abstract static class PyType_ReadyNode extends PythonBuiltinNode {
        @Child WriteAttributeToObjectNode writeNode = WriteAttributeToObjectNode.create();

        @Specialization
        PythonClass run(TruffleObject typestruct, PythonClass metaClass, PTuple baseClasses, String name, String doc) {
            Object[] array = baseClasses.getArray();
            PythonClass[] bases = new PythonClass[array.length];
            for (int i = 0; i < array.length; i++) {
                bases[i] = (PythonClass) array[i];
            }

            PythonClass cclass = factory().createNativeClassWrapper(typestruct, metaClass, name, bases);
            writeNode.execute(cclass, SpecialAttributeNames.__DOC__, doc);
            return cclass;
        }

        @Specialization
        PythonClass run(TruffleObject typestruct, PythonClass metaClass, PTuple baseClasses, PString name, PString doc) {
            return run(typestruct, metaClass, baseClasses, name.getValue(), doc.getValue());
        }
    }

    static class ExternalFunctionNode extends RootNode {
        private final TruffleObject cwrapper;
        private final TruffleObject callable;
        private final String name;
        @CompilationFinal PythonContext ctxt;
        @Child private Node executeNode;
        @Child CExtNodes.ToSulongNode toSulongNode = CExtNodes.ToSulongNode.create();
        @Child CExtNodes.AsPythonObjectNode asPythonObjectNode = CExtNodes.AsPythonObjectNode.create();
        @Child private Node isNullNode = Message.IS_NULL.createNode();
        @Child private PNativeToPTypeNode fromForeign = PNativeToPTypeNode.create();

        @Child private PythonObjectFactory factory = PythonObjectFactory.create();

        public ExternalFunctionNode(PythonLanguage lang, String name, TruffleObject cwrapper, TruffleObject callable) {
            super(lang);
            this.name = name;
            this.cwrapper = cwrapper;
            this.callable = callable;
            if (cwrapper != null) {
                this.executeNode = Message.createExecute(3).createNode();
            } else {
                this.executeNode = Message.createExecute(2).createNode();
            }
        }

        public TruffleObject getCallable() {
            return callable;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] frameArgs = frame.getArguments();
            try {
                TruffleObject fun;
                Object[] arguments;

                if (cwrapper != null) {
                    fun = cwrapper;
                    arguments = new Object[1 + frameArgs.length - PArguments.USER_ARGUMENTS_OFFSET];
                    arguments[0] = callable;
                    for (int i = 1; i < arguments.length; i++) {
                        arguments[i] = toSulongNode.execute(frameArgs[i + PArguments.USER_ARGUMENTS_OFFSET - 1]);
                    }
                } else {
                    fun = callable;
                    arguments = new Object[frameArgs.length - PArguments.USER_ARGUMENTS_OFFSET];
                    for (int i = 0; i < arguments.length; i++) {
                        arguments[i] = toSulongNode.execute(frameArgs[i + PArguments.USER_ARGUMENTS_OFFSET]);
                    }
                }
                return fromNative(asPythonObjectNode.execute(checkFunctionResult(ForeignAccess.sendExecute(executeNode, fun, arguments))));
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new RuntimeException(e.toString());
            }
        }

        private Object fromNative(Object result) {
            return fromForeign.executeConvert(result);
        }

        public final PythonCore getCore() {
            return getContext().getCore();
        }

        public final PythonContext getContext() {
            if (ctxt == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                ctxt = PythonLanguage.getContext();
            }
            return ctxt;
        }

        @Override
        public String getName() {
            return name;
        }

        // roughly equivalent to _Py_CheckFunctionResult in Objects/call.c
        private Object checkFunctionResult(Object result) {
            PException currentException = getContext().getCurrentException();
            boolean errOccurred = currentException != null;
            if (PGuards.isForeignObject(result) && ForeignAccess.sendIsNull(isNullNode, (TruffleObject) result) || result == PNone.NO_VALUE) {
                if (!errOccurred) {
                    throw getCore().raise(PythonErrorType.SystemError, this, "%s returned NULL without setting an error", name);
                } else {
                    throw currentException;
                }
            } else if (errOccurred) {
                throw getCore().raise(PythonErrorType.SystemError, this, "%s returned a result with an error set", name);
            }
            return result;
        }
    }

    abstract static class PNativeToPTypeNode extends PForeignToPTypeNode {

        @Specialization
        protected static PythonAbstractObject fromNativeNone(PythonObjectNativeWrapper nativeWrapper) {
            return nativeWrapper.getPythonObject();
        }

        public static PNativeToPTypeNode create() {
            return PNativeToPTypeNodeGen.create();
        }
    }

    @Builtin(name = "Py_NoValue", fixedNumOfArguments = 0)
    @GenerateNodeFactory
    abstract static class Py_NoValue extends PythonBuiltinNode {
        @Specialization
        PNone doNoValue() {
            return PNone.NO_VALUE;
        }
    }

    @Builtin(name = "Py_None", fixedNumOfArguments = 0)
    @GenerateNodeFactory
    abstract static class PyNoneNode extends PythonBuiltinNode {
        @Specialization
        PNone doNativeNone() {
            return PNone.NONE;
        }
    }

    abstract static class NativeBuiltin extends PythonBuiltinNode {

        protected void transformToNative(PException p) {
            p.getExceptionObject().reifyException();
            getContext().setCurrentException(p);
        }

        protected <T> T raiseNative(T defaultValue, PythonErrorType errType, String fmt, Object... args) {
            try {
                throw raise(errType, fmt, args);
            } catch (PException p) {
                transformToNative(p);
                return defaultValue;
            }
        }

        protected boolean isByteArray(TruffleObject o) {
            Object hostObject = getContext().getEnv().asHostObject(o);
            return hostObject instanceof byte[];
        }

        protected byte[] getByteArray(TruffleObject o) {
            Object hostObject = getContext().getEnv().asHostObject(o);
            return (byte[]) hostObject;
        }

        protected Object raiseBadArgument(Object errorMarker) {
            return raiseNative(errorMarker, PythonErrorType.TypeError, "bad argument type for built-in operation");
        }
    }

    abstract static class NativeUnicodeBuiltin extends NativeBuiltin {
        private static final int NATIVE_ORDER = 0;
        private static Charset UTF32;
        private static Charset UTF32LE;
        private static Charset UTF32BE;

        protected static Charset getUTF32Charset(int byteorder) {
            String utf32Name = getUTF32Name(byteorder);
            if (byteorder == NativeUnicodeBuiltin.NATIVE_ORDER) {
                if (UTF32 == null) {
                    UTF32 = Charset.forName(utf32Name);
                }
                return UTF32;
            } else if (byteorder < NativeUnicodeBuiltin.NATIVE_ORDER) {
                if (UTF32LE == null) {
                    UTF32LE = Charset.forName(utf32Name);
                }
                return UTF32LE;
            }
            if (UTF32BE == null) {
                UTF32BE = Charset.forName(utf32Name);
            }
            return UTF32BE;
        }

        protected static String getUTF32Name(int byteorder) {
            String csName;
            if (byteorder == 0) {
                csName = "UTF-32";
            } else if (byteorder < 0) {
                csName = "UTF-32LE";
            } else {
                csName = "UTF-32BE";
            }
            return csName;
        }
    }

    @Builtin(name = "TrufflePInt_AsPrimitive", fixedNumOfArguments = 4)
    @GenerateNodeFactory
    abstract static class TrufflePInt_AsPrimitive extends NativeBuiltin {

        @Specialization
        Object doPInt(int obj, int signed, long targetTypeSize, @SuppressWarnings("unused") String targetTypeName) {
            if (targetTypeSize == 4) {
                return obj;
            } else if (targetTypeSize == 8) {
                if (signed != 0) {
                    return (long) obj;
                } else {
                    return obj & 0xFFFFFFFFL;
                }
            } else {
                throw raise(PythonErrorType.SystemError, "Unsupported target size: %d", targetTypeSize);
            }
        }

        @Specialization
        Object doPInt(long obj, @SuppressWarnings("unused") int signed, long targetTypeSize, String targetTypeName) {
            if (targetTypeSize == 4) {
                throw raise(PythonErrorType.OverflowError, "Python int too large to convert to C %s", targetTypeName);
            } else if (targetTypeSize == 8) {
                return obj;
            } else {
                throw raise(PythonErrorType.SystemError, "Unsupported target size: %d", targetTypeSize);
            }
        }

        @Specialization
        Object doPInt(PInt obj, int signed, long targetTypeSize, String targetTypeName) {
            try {
                if (targetTypeSize == 4) {
                    if (signed != 0) {
                        return obj.intValueExact();
                    } else if (obj.bitCount() <= 32) {
                        return obj.intValue();
                    } else {
                        throw new ArithmeticException();
                    }
                } else if (targetTypeSize == 8) {
                    if (signed != 0) {
                        return obj.longValueExact();
                    } else if (obj.bitCount() <= 64) {
                        return obj.longValue();
                    } else {
                        throw new ArithmeticException();
                    }
                } else {
                    throw raise(PythonErrorType.SystemError, "Unsupported target size: %d", targetTypeSize);
                }

            } catch (ArithmeticException e) {
                try {
                    throw raise(PythonErrorType.OverflowError, "Python int too large to convert to C %s", targetTypeName);
                } catch (PException p) {
                    p.getExceptionObject().reifyException();
                    getContext().setCurrentException(p);
                    return -1;
                }
            }
        }

        @Specialization(guards = {"!isInteger(obj)", "!isPInt(obj)"})
        @SuppressWarnings("unused")
        int doGeneric(Object obj, boolean signed, int targetTypeSize, String targetTypeName) {
            return raiseNative(-1, PythonErrorType.TypeError, "an integer is required", obj);
        }
    }

    @Builtin(name = "TrufflePFloat_AsPrimitive", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class TrufflePFloat_AsPrimitive extends NativeBuiltin {
        @Specialization
        double doDouble(double d) {
            return d;
        }

        @Specialization
        double doPFloat(PFloat obj) {
            return obj.getValue();
        }

        @Fallback
        double doGeneric(Object obj) {
            return raiseNative(-1.0, PythonErrorType.TypeError, "must be real number, not %p", obj);
        }

    }

    @Builtin(name = "PyTruffle_Unicode_FromWchar", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class PyTruffle_Unicode_FromWchar extends NativeUnicodeBuiltin {
        @Specialization(guards = "isByteArray(o)")
        @TruffleBoundary
        Object doBytes(TruffleObject o, long elementSize, Object errorMarker) {
            try {
                ByteBuffer bytes = ByteBuffer.wrap(getByteArray(o));
                CharBuffer decoded;
                if (elementSize == 2L) {
                    decoded = bytes.asCharBuffer();
                } else if (elementSize == 4L) {
                    decoded = getUTF32Charset(0).newDecoder().decode(bytes);
                } else {
                    return raiseNative(errorMarker, PythonErrorType.ValueError, "unsupported 'wchar_t' size; was: %d", elementSize);
                }
                return decoded.toString();
            } catch (CharacterCodingException e) {
                return raiseNative(errorMarker, PythonErrorType.UnicodeError, e.getMessage());
            } catch (IllegalArgumentException e) {
                return raiseNative(errorMarker, PythonErrorType.LookupError, e.getMessage());
            }
        }
    }

    @Builtin(name = "PyTruffle_Unicode_FromUTF8", fixedNumOfArguments = 2)
    @GenerateNodeFactory
    @ImportStatic(Message.class)
    abstract static class PyTruffle_Unicode_FromUTF8 extends NativeBuiltin {

        @Specialization(guards = "isByteArray(o)")
        @TruffleBoundary
        Object doBytes(TruffleObject o, Object errorMarker) {
            try {
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                CharBuffer cbuf = decoder.decode(ByteBuffer.wrap(getByteArray(o)));
                return cbuf.toString();
            } catch (CharacterCodingException e) {
                return raiseNative(errorMarker, PythonErrorType.UnicodeError, e.getMessage());
            }
        }
    }

    abstract static class NativeEncoderNode extends NativeBuiltin {
        private final Charset charset;

        protected NativeEncoderNode(Charset charset) {
            this.charset = charset;
        }

        @Specialization(guards = "isNoValue(errors)")
        Object doUnicode(PString s, @SuppressWarnings("unused") PNone errors, Object error_marker) {
            return doUnicode(s, "strict", error_marker);
        }

        @Specialization
        @TruffleBoundary
        Object doUnicode(PString s, String errors, Object error_marker) {
            try {
                CharsetEncoder encoder = charset.newEncoder();
                CodingErrorAction action = BytesBuiltins.toCodingErrorAction(errors, this);
                encoder.onMalformedInput(action).onUnmappableCharacter(action);
                CharBuffer buf = CharBuffer.allocate(s.len());
                buf.put(s.getValue());
                buf.flip();
                ByteBuffer encoded = encoder.encode(buf);
                byte[] barr = new byte[encoded.remaining()];
                encoded.get(barr);
                return factory().createBytes(barr);
            } catch (PException e) {
                transformToNative(e);
                return error_marker;
            } catch (CharacterCodingException e) {
                return raiseNative(error_marker, PythonErrorType.UnicodeEncodeError, e.getMessage());
            }
        }

        @Fallback
        Object doUnicode(@SuppressWarnings("unused") Object s, @SuppressWarnings("unused") Object errors, Object errorMarker) {
            return raiseBadArgument(errorMarker);
        }
    }

    @Builtin(name = "_PyUnicode_AsUTF8String", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class _PyUnicode_AsUTF8String extends NativeEncoderNode {

        protected _PyUnicode_AsUTF8String() {
            super(StandardCharsets.UTF_8);
        }
    }

    @Builtin(name = "_PyTruffle_Unicode_AsLatin1String", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class _PyTruffle_Unicode_AsLatin1String extends NativeEncoderNode {
        protected _PyTruffle_Unicode_AsLatin1String() {
            super(StandardCharsets.ISO_8859_1);
        }
    }

    @Builtin(name = "_PyTruffle_Unicode_AsASCIIString", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class _PyTruffle_Unicode_AsASCIIString extends NativeEncoderNode {
        protected _PyTruffle_Unicode_AsASCIIString() {
            super(StandardCharsets.US_ASCII);
        }
    }

    @Builtin(name = "PyTruffle_Unicode_AsUnicodeAndSize", fixedNumOfArguments = 3)
    @GenerateNodeFactory
    abstract static class PyTruffle_Unicode_AsUnicodeAndSize extends NativeBuiltin {
        @Specialization
        @TruffleBoundary
        Object doUnicode(PString s) {
            char[] charArray = s.getValue().toCharArray();
            // stuff into byte[]
            ByteBuffer allocate = ByteBuffer.allocate(charArray.length * 2);
            for (int i = 0; i < charArray.length; i++) {
                allocate.putChar(charArray[i]);
            }
            return getContext().getEnv().asGuestValue(allocate.array());
        }
    }

    @Builtin(name = "PyTruffle_Unicode_DecodeUTF32", fixedNumOfArguments = 4)
    @GenerateNodeFactory
    abstract static class PyTruffle_Unicode_DecodeUTF32 extends NativeUnicodeBuiltin {

        @Specialization(guards = "isByteArray(o)")
        Object doUnicode(TruffleObject o, @SuppressWarnings("unused") PNone errors, int byteorder, Object errorMarker) {
            return doUnicode(o, "strict", byteorder, errorMarker);
        }

        @Specialization(guards = "isByteArray(o)")
        @TruffleBoundary
        Object doUnicode(TruffleObject o, String errors, int byteorder, Object errorMarker) {
            try {
                CharsetDecoder decoder = getUTF32Charset(byteorder).newDecoder();
                CodingErrorAction action = BytesBuiltins.toCodingErrorAction(errors, this);
                CharBuffer decode = decoder.onMalformedInput(action).onUnmappableCharacter(action).decode(ByteBuffer.wrap(getByteArray(o)));
                return decode.toString();
            } catch (CharacterCodingException e) {
                return raiseNative(errorMarker, PythonErrorType.UnicodeEncodeError, e.getMessage());
            } catch (IllegalArgumentException e) {
                String csName = getUTF32Name(byteorder);
                return raiseNative(errorMarker, PythonErrorType.LookupError, "unknown encoding: " + csName);
            }
        }
    }

    @Builtin(name = "PyTruffle_Unicode_AsWideChar", fixedNumOfArguments = 4)
    @GenerateNodeFactory
    abstract static class PyTruffle_Unicode_AsWideChar extends NativeUnicodeBuiltin {
        @Child private UnicodeAsWideCharNode asWideCharNode;

        @Specialization
        Object doUnicode(PString s, long elementSize, long elements, Object errorMarker) {
            return doUnicode(s.getValue(), elementSize, elements, errorMarker);
        }

        @Specialization
        @TruffleBoundary
        Object doUnicode(String s, long elementSize, long elements, Object errorMarker) {
            try {
                if (asWideCharNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    asWideCharNode = insert(UnicodeAsWideCharNode.create());
                }

                PBytes wchars = asWideCharNode.execute(s, elementSize, elements);
                if (wchars != null) {
                    return wchars;
                } else {
                    return raiseNative(errorMarker, PythonErrorType.ValueError, "unsupported wchar size; was: %d", elementSize);
                }
            } catch (IllegalArgumentException e) {
                // TODO
                return raiseNative(errorMarker, PythonErrorType.LookupError, e.getMessage());
            }
        }
    }

    @Builtin(name = "PyTruffle_Bytes_AsString", fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class PyTruffle_Bytes_AsString extends NativeBuiltin {
        @Specialization
        Object doBytes(PBytes bytes, @SuppressWarnings("unused") Object errorMarker) {
            // according to Python's documentation, the last byte is always '0x00'
            byte[] store = bytes.getInternalByteArray();
            byte[] nativeBytes = Arrays.copyOf(store, store.length + 1);
            assert nativeBytes[nativeBytes.length - 1] == 0;
            return getContext().getEnv().asGuestValue(nativeBytes);
        }

        @Specialization
        Object doUnicode(PString str, @SuppressWarnings("unused") Object errorMarker) {
            // according to Python's documentation, the last byte is always '0x00'
            byte[] store = str.getValue().getBytes();
            byte[] nativeBytes = Arrays.copyOf(store, store.length + 1);
            assert nativeBytes[nativeBytes.length - 1] == 0;
            return getContext().getEnv().asGuestValue(nativeBytes);
        }

        @Fallback
        Object doUnicode(@SuppressWarnings("unused") Object o, Object errorMarker) {
            return errorMarker;
        }
    }

    @Builtin(name = "PyHash_Imag", fixedNumOfArguments = 0)
    @GenerateNodeFactory
    abstract static class PyHashImagNode extends PythonBuiltinNode {
        @Specialization
        long getHash() {
            return PComplex.IMAG_MULTIPLIER;
        }
    }

    @Builtin(name = "PyTruffle_GetTpFlags", fixedNumOfArguments = 1)
    @GenerateNodeFactory
    abstract static class PyTruffle_GetTpFlags extends NativeBuiltin {

        @Child private GetClassNode getClassNode;

        @Specialization
        long doPythonObject(PythonObjectNativeWrapper nativeWrapper) {
            PythonClass pclass = getClassNode().execute(nativeWrapper.getPythonObject());
            return pclass.getFlags();
        }

        @Specialization
        long doPythonObject(PythonAbstractObject object) {
            PythonClass pclass = getClassNode().execute(object);
            return pclass.getFlags();
        }

        private GetClassNode getClassNode() {
            if (getClassNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getClassNode = insert(GetClassNode.create());
            }
            return getClassNode;
        }
    }

    @Builtin(name = "PyTruffle_Set_Ptr", fixedNumOfArguments = 2)
    @GenerateNodeFactory
    abstract static class PyTruffle_Set_Ptr extends NativeBuiltin {

        @Specialization
        PythonObjectNativeWrapper doPythonObject(PythonObjectNativeWrapper nativeWrapper, Object ptr) {
            nativeWrapper.setNativePointer(ptr);
            return nativeWrapper;
        }
    }
}
