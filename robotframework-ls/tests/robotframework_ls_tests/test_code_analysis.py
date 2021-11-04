def _collect_errors(workspace, doc, data_regression, basename=None, config=None):
    from robotframework_ls.impl.completion_context import CompletionContext
    from robotframework_ls.impl.code_analysis import collect_analysis_errors

    completion_context = CompletionContext(doc, workspace=workspace.ws, config=config)

    errors = [
        error.to_lsp_diagnostic()
        for error in collect_analysis_errors(completion_context)
    ]
    data_regression.check(errors, basename=basename)


def test_keywords_analyzed(workspace, libspec_manager, data_regression):

    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = doc.source + (
        "\n    This keyword does not exist" "\n    [Teardown]    Also not there"
    )

    _collect_errors(workspace, doc, data_regression)


def test_keywords_analyzed_templates(workspace, libspec_manager, data_regression):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = """*** Settings ***
Test Template    this is not there"""

    _collect_errors(workspace, doc, data_regression)


def test_no_lib_name(workspace, libspec_manager, data_regression):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = """*** Settings ***
Library
Resource

*** Keywords ***
I check ${cmd}
    Log    ${cmd}
"""

    _collect_errors(workspace, doc, data_regression)


def test_keywords_with_vars_no_error(workspace, libspec_manager, data_regression):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = (
        doc.source
        + """
    I check ls
    I execute "ls" rara "-lh"

*** Keywords ***
I check ${cmd}
    Log    ${cmd}

I execute "${cmd}" rara "${opts}"
    Log    ${cmd} ${opts}
    
"""
    )

    _collect_errors(workspace, doc, data_regression)


def test_keywords_in_args(workspace, libspec_manager, data_regression):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = (
        doc.source
        + """
    Run Keyword If    ${var}    This does not exist    
"""
    )

    _collect_errors(workspace, doc, data_regression)


def test_keywords_in_args_no_error_with_var(
    workspace, libspec_manager, data_regression
):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = (
        doc.source
        + """
    Run Keyword    ${var}
"""
    )

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_keywords_with_prefix_no_error(workspace, libspec_manager, data_regression):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    # Ignore bdd-related prefixes (see: robotframework_ls.impl.robot_constants.BDD_PREFIXES)
    doc.source = (
        doc.source
        + """
    given I check ls
    then I execute

*** Keywords ***
I check ${cmd}
    Log    ${cmd}

I execute
    Log    foo
"""
    )

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_keywords_prefixed_by_library(workspace, libspec_manager, data_regression):
    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case4.robot")

    doc.source = """*** Settings ***
Library    String
Library    Collections
Resource    case4resource.txt

*** Test Cases ***
Test
    BuiltIn.Log    Logging
    case4resource3.Yet Another Equal Redefined
    String.Should Be Titlecase    Hello World
    ${list}=    BuiltIn.Create List    1    2
    Collections.Append To List    ${list}    3"""

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_keywords_prefixed_with_alias(workspace, libspec_manager, data_regression):
    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case4.robot")

    doc.source = """*** Settings ***
Library    Collections    WITH NAME    Col1

*** Test Cases ***
Test
    Col1.Append To List    ${list}    3"""

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_keywords_name_matches(workspace, libspec_manager, data_regression):
    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case4.robot")

    doc.source = """*** Settings ***
Library    Collections

*** Test Cases ***
Test
    AppendToList    ${list}    3"""

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_resource_does_not_exist(workspace, libspec_manager, data_regression):
    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case4.robot")

    doc.source = """*** Settings ***
Library    DoesNotExist
Library    .
Library    ..
Library    ../
Resource    does_not_exist.txt
Resource    ${foo}/does_not_exist.txt
Resource    ../does_not_exist.txt
Resource    .
Resource    ..
Resource    ../
Resource    ../../does_not_exist.txt
Resource    case4resource.txt

*** Test Cases ***
Test
    case4resource3.Yet Another Equal Redefined"""

    from robotframework_ls.robot_config import RobotConfig

    config = RobotConfig()
    # Note: we don't give errors if we can't resolve a resource.
    _collect_errors(workspace, doc, data_regression, basename="no_error", config=config)


def test_casing_on_filename(workspace, libspec_manager, data_regression):
    from robocorp_ls_core.protocols import IDocument
    from pathlib import Path

    # i.e.: Importing a python library with capital letters fails #143

    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc: IDocument = workspace.get_doc("case4.robot")
    p = Path(doc.path)
    (p.parent / "myPythonKeywords.py").write_text(
        """
class myPythonKeywords(object):
    ROBOT_LIBRARY_VERSION = 1.0
    def __init__(self):
        pass

    def Uppercase_Keyword (self):
        return "Uppercase does not work"
"""
    )

    doc.source = """*** Settings ***
Library    myPythonKeywords.py

*** Test Cases ***
Test
    Uppercase Keyword"""

    from robotframework_ls.robot_config import RobotConfig

    config = RobotConfig()
    # Note: we don't give errors if we can't resolve a resource.
    _collect_errors(workspace, doc, data_regression, basename="no_error", config=config)


def test_empty_teardown(workspace, libspec_manager, data_regression):
    workspace.set_root("case4", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case4.robot")

    doc.source = """
*** Test Cases ***
My Test 1
  [Teardown]
"""

    _collect_errors(workspace, doc, data_regression, basename="no_error")


def test_code_analysis_lib_with_params(
    workspace, libspec_manager, cases, data_regression
):
    from robotframework_ls.robot_config import RobotConfig
    from robotframework_ls.impl.robot_lsp_constants import OPTION_ROBOT_PYTHONPATH

    workspace.set_root("case_params_on_lib", libspec_manager=libspec_manager)

    caseroot = cases.get_path("case_params_on_lib")
    config = RobotConfig()
    config.update({"robot": {"pythonpath": [caseroot]}})
    assert config.get_setting(OPTION_ROBOT_PYTHONPATH, list, []) == [caseroot]
    libspec_manager.config = config

    doc = workspace.get_doc("case_params_on_lib.robot")

    _collect_errors(workspace, doc, data_regression, basename="no_error", config=config)


def test_code_analysis_search_pythonpath(
    workspace, libspec_manager, cases, data_regression
):
    import sys

    add_to_pythonpath = cases.get_path("case_search_pythonpath_resource/resources")
    sys.path.append(add_to_pythonpath)

    try:
        workspace.set_root(
            "case_search_pythonpath_resource", libspec_manager=libspec_manager
        )

        doc = workspace.get_doc("case_search_pythonpath.robot")

        _collect_errors(workspace, doc, data_regression, basename="no_error")
    finally:
        sys.path.remove(add_to_pythonpath)


def test_code_analysis_template_name_keyword(
    workspace, libspec_manager, data_regression
):
    workspace.set_root("case1", libspec_manager=libspec_manager)
    doc = workspace.get_doc("case1.robot")
    doc.source = """
*** Keyword ***
Example Keyword

*** Test Cases **
Normal test case
    Example keyword    first argument    second argument

Templated test case
    [Template]    Example k
    first argument    second argument
"""

    _collect_errors(workspace, doc, data_regression)
