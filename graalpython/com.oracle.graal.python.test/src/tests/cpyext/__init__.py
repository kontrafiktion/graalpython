# Copyright (c) 2018, Oracle and/or its affiliates.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or data
# (collectively the "Software"), free of charge and under any and all copyright
# rights in the Software, and any and all patent rights owned or freely
# licensable by each licensor hereunder covering either (i) the unmodified
# Software as contributed to or provided by such licensor, or (ii) the Larger
# Works (as defined below), to deal in both
#
# (a) the Software, and
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
#     one is included with the Software (each a "Larger Work" to which the
#     Software is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

import sys
os = sys.modules.get("posix", sys.modules.get("nt", None))
if os is None:
    raise ImportError("posix or nt module is required in builtin modules")
__dir__ = __file__.rpartition("/")[0]

GRAALPYTHON = sys.implementation.name == "graalpython"


def unhandled_error_compare(x, y):
    if (isinstance(x, BaseException) and isinstance(y, BaseException)):
        return type(x) == type(y)
    else:
        return x == y


class CPyExtTestCase():

    def setUp(self):
        for typ in type(self).mro():
            for k, v in typ.__dict__.items():
                if k.startswith("test_"):
                    modname = k.replace("test_", "")
                    if k.startswith("test_graalpython_"):
                        if not GRAALPYTHON:
                            continue
                        else:
                            modname = k.replace("test_graalpython_", "")
                    self.compile_module(modname)


def ccompile(self, name):
    from distutils.core import setup, Extension
    module = Extension(name, sources=['%s/%s.c' % (__dir__, name)])
    args = ['--quiet', 'build', 'install_lib', '-f', '--install-dir=%s' % __dir__]
    setup(
        script_name='setup',
        script_args=args,
        name=name,
        version='1.0',
        description='',
        ext_modules=[module]
    )


c_template = """
#include <Python.h>
{defines}

{customcode}

static PyObject* test_{capifunction}(PyObject* module, PyObject* args) {{
    PyObject* ___arg;
    {argumentdeclarations};
    if (!PyArg_ParseTuple(args, "O", &___arg)) {{
        return NULL;
    }}

    if (strlen("{argspec}") > 0) {{
        if (!PyArg_ParseTuple(___arg, "{argspec}", {derefargumentnames})) {{
            return NULL;
        }}
    }} 
#ifdef SINGLEARG
    else {{
        {singleargumentname} = ___arg;
    }}
#endif
    
    return Py_BuildValue("{resultspec}", {callfunction}({argumentnames}));
}}

static PyMethodDef TestMethods[] = {{
    {{"test_{capifunction}", test_{capifunction}, METH_VARARGS, ""}},
    {{NULL, NULL, 0, NULL}}        /* Sentinel */
}};

static PyModuleDef testmodule = {{
    PyModuleDef_HEAD_INIT,
    "{capifunction}",
    "test module",
    -1,
    TestMethods,
    NULL, NULL, NULL, NULL
}};

PyMODINIT_FUNC
PyInit_{capifunction}(void)
{{
    return PyModule_Create(&testmodule);
}}
"""

c_template_void = """
#include <Python.h>
{defines}

{customcode}

static PyObject* test_{capifunction}(PyObject* module, PyObject* args) {{
    PyObject* ___arg;
    {argumentdeclarations};
    if (!PyArg_ParseTuple(args, "O", &___arg)) {{
        return NULL;
    }}

    if (strlen("{argspec}") > 0) {{
        if (!PyArg_ParseTuple(___arg, "{argspec}", {derefargumentnames})) {{
            return NULL;
        }}
    }} 
#ifdef SINGLEARG
    else {{
        {singleargumentname} = ___arg;
    }}
#endif
    {callfunction}({argumentnames});
    return Py_BuildValue("{resultspec}", {resultval});
}}

static PyMethodDef TestMethods[] = {{
    {{"test_{capifunction}", test_{capifunction}, METH_VARARGS, ""}},
    {{NULL, NULL, 0, NULL}}        /* Sentinel */
}};

static PyModuleDef testmodule = {{
    PyModuleDef_HEAD_INIT,
    "{capifunction}",
    "test module",
    -1,
    TestMethods,
    NULL, NULL, NULL, NULL
}};

PyMODINIT_FUNC
PyInit_{capifunction}(void)
{{
    return PyModule_Create(&testmodule);
}}
"""

c_template_multi_res = """
#include <Python.h>
{defines}

{customcode}

static PyObject* test_{capifunction}(PyObject* module, PyObject* args) {{
    PyObject* ___arg;
    {argumentdeclarations};
    {resultvardeclarations}
    {resulttype} res;

    if (!PyArg_ParseTuple(args, "O", &___arg)) {{
        return NULL;
    }}

    if (strlen("{argspec}") > 0) {{
        if (!PyArg_ParseTuple(___arg, "{argspec}", {derefargumentnames})) {{
            return NULL;
        }}
    }}
#ifdef SINGLEARG 
    else {{
        {singleargumentname} = ___arg;
    }}
#endif
    
    res = {callfunction}({argumentnames}{resultvarlocations});

    return Py_BuildValue("{resultspec}", res {resultvarnames});
}}

static PyMethodDef TestMethods[] = {{
    {{"test_{capifunction}", test_{capifunction}, METH_VARARGS, ""}},
    {{NULL, NULL, 0, NULL}}        /* Sentinel */
}};

static PyModuleDef testmodule = {{
    PyModuleDef_HEAD_INIT,
    "{capifunction}",
    "test module",
    -1,
    TestMethods,
    NULL, NULL, NULL, NULL
}};

PyMODINIT_FUNC
PyInit_{capifunction}(void)
{{
    return PyModule_Create(&testmodule);
}}
"""


class CPyExtFunction():

    def __init__(self, pfunc, parameters, template=c_template, cmpfunc=None, **kwargs):
        self.template = template
        self.pfunc = pfunc
        self.parameters = parameters
        kwargs["name"] = kwargs["name"] if "name" in kwargs else None
        self.name = kwargs["name"]
        if "code" in kwargs:
            kwargs["customcode"] = kwargs["code"]
            del kwargs["code"]
        else:
            kwargs["customcode"] = ""
        kwargs["argspec"] = kwargs["argspec"] if "argspec" in kwargs else ""
        kwargs["arguments"] = kwargs["arguments"] if "arguments" in kwargs else ["PyObject* argument"]
        kwargs["parseargs"] = kwargs["parseargs"] if "parseargs" in kwargs else kwargs["arguments"]
        kwargs["resultspec"] = kwargs["resultspec"] if "resultspec" in kwargs else "O"
        self.formatargs = kwargs
        self.cmpfunc = cmpfunc or self.do_compare

    def do_compare(self, x, y):
        if isinstance(x, BaseException):
            x = repr(x)
        if isinstance(y, BaseException):
            y = repr(y)
        return x == y

    def create_module(self, name=None):
        fargs = self.formatargs
        if name:
            fargs["capifunction"] = name
        elif "name" in fargs:
            fargs["capifunction"] = fargs["name"]
            del fargs["name"]
        self.name = fargs["capifunction"]

        self._insert(fargs, "argumentdeclarations", ";".join(fargs["parseargs"]))
        self._insert(fargs, "argumentnames", ", ".join(arg.rpartition(" ")[2] for arg in fargs["arguments"]))
        self._insert(fargs, "singleargumentname", fargs["arguments"][0].rpartition(" ")[2])
        self._insert(fargs, "derefargumentnames", ", ".join("&" + arg.rpartition(" ")[2].partition("=")[0] for arg in fargs["arguments"]))
        self._insert(fargs, "callfunction", fargs["capifunction"])
        if len(fargs["argspec"]) == 0:
            fargs["defines"] = "#define SINGLEARG"
        else:
            fargs["defines"] = ""

        code = self.template.format(**fargs)

        with open("%s/%s.c" % (__dir__, self.name), "wb", buffering=0) as f:
            if GRAALPYTHON:
                f.write(code)
            else:
                f.write(bytes(code, 'utf-8'))

    def _insert(self, d, name, default_value):
        d[name] = d.get(name, default_value)

    def __repr__(self):
        return "<CPyExtFunction %s>" % self.name

    def test(self):
        sys.path.insert(0, __dir__)
        try:
            cmodule = __import__(self.name)
        finally:
            sys.path.pop(0)
        ctest = getattr(cmodule, "test_%s" % self.name)
        cargs = self.parameters()
        pargs = self.parameters()
        for i in range(len(cargs)):
            cresult = presult = None
            try:
                cresult = ctest(cargs[i])
            except BaseException as e:
                cresult = e

            try:
                presult = self.pfunc(pargs[i])
            except BaseException as e:
                presult = e

            if not self.cmpfunc:
                assert cresult == presult, ("%r == %r in %s" % (cresult, presult, self.name))
            else:
                assert self.cmpfunc(cresult, presult), ("%r == %r in %s" % (cresult, presult, self.name))

    def __get__(self, instance, typ=None):
        if typ is None:
            return self
        else:
            CPyExtFunction.test.__name__ = self.name
            return self.test


class CPyExtFunctionOutVars(CPyExtFunction):
    '''
    Some native function have output vars, i.e., take pointers to variables where to store results.
    This class supports this.
    Set 'resultvars' to declare the output vars.
    '''

    def __init__(self, pfunc, parameters, template=c_template_multi_res, **kwargs):
        super(CPyExtFunctionOutVars, self).__init__(pfunc, parameters, **kwargs)
        self.template = template

    def create_module(self, name=None):
        fargs = self.formatargs
        if "resultvars" not in fargs:
            fargs["resultvars"] = ""

        if "resultvardeclarations" not in fargs:
            if len(fargs["resultvars"]):
                fargs["resultvardeclarations"] = ";".join(fargs["resultvars"]) + ";"
            else:
                fargs["resultvardeclarations"] = ""
        if "resultvarnames" not in fargs:
            if len(fargs["resultvars"]):
                fargs["resultvarnames"] = ", ".join(arg.rpartition(" ")[2] for arg in fargs["resultvars"])
            else:
                fargs["resultvarnames"] = ""
        if len(fargs["resultvarnames"]) and not fargs["resultvarnames"].startswith(","):
            fargs["resultvarnames"] = ", " + fargs["resultvarnames"]

        if "resultvarlocations" not in fargs:
                    fargs["resultvarlocations"] = ", ".join("&" + arg.rpartition(" ")[2] for arg in fargs["resultvars"])
        if "resulttype" not in fargs:
                    fargs["resulttype"] = "void*"
        if len(fargs["resultvarlocations"]):
            fargs["resultvarlocations"] = ", " + fargs["resultvarlocations"]
        self._insert(fargs, "customcode", "")
        super(CPyExtFunctionOutVars, self).create_module(name)


class CPyExtFunctionVoid(CPyExtFunction):

    def __init__(self, pfunc, parameters, template=c_template_void, **kwargs):
        super(CPyExtFunctionVoid, self).__init__(pfunc, parameters, **kwargs)
        self.template = template

    def create_module(self, name=None):
        fargs = self.formatargs
        if "resultval" not in fargs:
            fargs["resultval"] = "Py_None"
        super(CPyExtFunctionVoid, self).create_module(name)


CPyExtTestCase.compile_module = ccompile
