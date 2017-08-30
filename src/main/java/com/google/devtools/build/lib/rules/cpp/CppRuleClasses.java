// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_PIC_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_HEADER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.C_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.INTERFACE_SHARED_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.SHARED_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.VERSIONED_SHARED_LIBRARY;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.LanguageDependentFragment.LibraryLanguage;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.PatchTransition;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabel;
import com.google.devtools.build.lib.packages.Attribute.Transition;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionFactory;
import com.google.devtools.build.lib.rules.cpp.transitions.DisableLipoTransition;
import com.google.devtools.build.lib.rules.cpp.transitions.EnableLipoTransition;
import com.google.devtools.build.lib.rules.cpp.transitions.LipoContextCollectorTransition;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OsUtils;

/**
 * Rule class definitions for C++ rules.
 */
public class CppRuleClasses {
  /**
   * Implementation for the :lipo_context_collector attribute.
   */
  public static final LateBoundLabel<BuildConfiguration> LIPO_CONTEXT_COLLECTOR =
      new LateBoundLabel<BuildConfiguration>() {
    @Override
    public Label resolve(Rule rule, AttributeMap attributes, BuildConfiguration configuration) {
      // This attribute connects a target to the LIPO context target configured with the
      // lipo input collector configuration.
      CppConfiguration cppConfiguration = configuration.getFragment(CppConfiguration.class);
      return cppConfiguration.isLipoOptimization() ? cppConfiguration.getLipoContextLabel() : null;
    }
  };

  /**
   * Configuration transitions required by LIPO.
   */
  public enum LipoTransition implements Transition {
    /**
     * LIPO context collector.
     *
     * <p>This configuration transition leads into a configuration that is used for collecting
     * C++ compilation contexts for LIPO builds so that e.g. an include path entry required by an
     * inlined function is there when the place is compiled where it is inlined at.
     */
    LIPO_COLLECTOR,

    /**
     * Transition used for switching back to the LIPO-optimized configuration.
     */
    TARGET_CONFIG_FOR_LIPO;

    @Override
    public boolean defaultsToSelf() {
      return true;
    }
  }

  /**
   * Declares the implementations for C++ transition enums.
   *
   * <p>New transitions should extend {@link PatchTransition}, which avoids the need for this map.
   */
  public static final ImmutableMap<Transition, Transition> DYNAMIC_TRANSITIONS_MAP =
      ImmutableMap.of(
          Attribute.ConfigurationTransition.DATA, DisableLipoTransition.INSTANCE,
          LipoTransition.LIPO_COLLECTOR, LipoContextCollectorTransition.INSTANCE
      );


  /**
   * Rule transition factory that enables LIPO on the LIPO context binary (i.e. applies a DATA ->
   * TARGET transition).
   *
   * <p>This is how dynamic configurations enable LIPO on the LIPO context.
   */
  public static final RuleTransitionFactory LIPO_ON_DEMAND =
      new RuleTransitionFactory() {
        @Override
        public Attribute.Transition buildTransitionFor(Rule rule) {
          return new EnableLipoTransition(rule.getLabel());
        }
      };

  /**
   * Label of a pseudo-filegroup that contains all crosstool and libcfiles for all configurations,
   * as specified on the command-line.
   */
  public static final String CROSSTOOL_LABEL = "//tools/cpp:toolchain";

  public static final LateBoundLabel<BuildConfiguration> DEFAULT_MALLOC =
      new LateBoundLabel<BuildConfiguration>() {
        @Override
        public Label resolve(Rule rule, AttributeMap attributes, BuildConfiguration configuration) {
          return configuration.getFragment(CppConfiguration.class).customMalloc();
        }
      };

  public static LateBoundLabel<BuildConfiguration> ccToolchainAttribute(
      RuleDefinitionEnvironment env) {
    return new LateBoundLabel<BuildConfiguration>(
        env.getToolsLabel(CROSSTOOL_LABEL), CppConfiguration.class) {
      @Override
      public Label resolve(Rule rule, AttributeMap attributes, BuildConfiguration configuration) {
        return configuration.getFragment(CppConfiguration.class).getCcToolchainRuleLabel();
      }
    };
  }

  // Artifacts of these types are discarded from the 'hdrs' attribute in cc rules
  static final FileTypeSet DISALLOWED_HDRS_FILES = FileTypeSet.of(
      ARCHIVE,
      PIC_ARCHIVE,
      ALWAYS_LINK_LIBRARY,
      ALWAYS_LINK_PIC_LIBRARY,
      SHARED_LIBRARY,
      INTERFACE_SHARED_LIBRARY,
      VERSIONED_SHARED_LIBRARY,
      OBJECT_FILE,
      PIC_OBJECT_FILE);

  /**
   * The set of instrumented source file types; keep this in sync with the list above. Note that
   * extension-less header files cannot currently be declared, so we cannot collect coverage for
   * those.
   */
  static final InstrumentationSpec INSTRUMENTATION_SPEC = new InstrumentationSpec(
      FileTypeSet.of(CPP_SOURCE, C_SOURCE, CPP_HEADER, ASSEMBLER_WITH_C_PREPROCESSOR,
          ASSEMBLER))
      .withSourceAttributes("srcs", "hdrs")
      .withDependencyAttributes("deps", "data");

  public static final LibraryLanguage LANGUAGE = new LibraryLanguage("C++");

  /** Implicit outputs for cc_binary rules. */
  public static final SafeImplicitOutputsFunction CC_BINARY_STRIPPED =
      fromTemplates("%{name}.stripped" + OsUtils.executableExtension());

  // Used for requesting dwp "debug packages".
  public static final SafeImplicitOutputsFunction CC_BINARY_DEBUG_PACKAGE =
      fromTemplates("%{name}.dwp");

  /** A string constant for the copts feature. */
  public static final String COPTS = "copts";

  /**
   * A string constant for the parse_headers feature.
   */
  public static final String PARSE_HEADERS = "parse_headers";

  /**
   * A string constant for the preprocess_headers feature.
   */
  public static final String PREPROCESS_HEADERS = "preprocess_headers";

  /**
   * A string constant for the module_maps feature; this is a precondition to the layering_check and
   * header_modules features.
   */
  public static final String MODULE_MAPS = "module_maps";

  /**
   * A string constant for the random_seed feature. This is used by gcc and Clangfor the
   * randomization of symbol names that are in the anonymous namespace but have external linkage.
   */
  public static final String RANDOM_SEED = "random_seed";

  /**
   * A string constant for the compile_action_flags_in_flag_set feature. This feature is just a
   * transitional feature which helps telling whether -c and -o options are already in flag_set of
   * action_config in CROSSTOOL file. Once the transition is done, it should be removed.
   */
  public static final String COMPILE_ACTION_FLAGS_IN_FLAG_SET = "compile_action_flags_in_flag_set";

  /**
   * A string constant for the dependency_file feature. This feature generates the .d file.
   */
  public static final String DEPENDENCY_FILE = "dependency_file";

  /**
   * A string constant for the module_map_home_cwd feature.
   */
  public static final String MODULE_MAP_HOME_CWD = "module_map_home_cwd";

  /**
   * A string constant for the module_map_without_extern_module feature.
   *
   * <p>This features is a transitional feature; enabling it means that generated module maps
   * will not have "extern module" declarations inside them; instead, the module maps need
   * to be passed via the dependent_module_map_files build variable.
   *
   * <p>This variable is phrased negatively to aid the roll-out: currently, the default is that
   * "extern module" declarations are generated.
   */
  public static final String MODULE_MAP_WITHOUT_EXTERN_MODULE = "module_map_without_extern_module";

  /**
   * A string constant for the layering_check feature.
   */
  public static final String LAYERING_CHECK = "layering_check";

  /** A string constant for the header_modules feature. */
  public static final String HEADER_MODULES = "header_modules";

  /** A string constant for the header_modules_compile feature. */
  public static final String HEADER_MODULE_COMPILE = "header_module_compile";
  
  /** A string constant for the header_module_codegen feature. */
  public static final String HEADER_MODULE_CODEGEN = "header_module_codegen";

  /**
   * A string constant for the compile_all_modules feature.
   */
  public static final String COMPILE_ALL_MODULES = "compile_all_modules";

  /**
   * A string constant for the exclude_private_headers_in_module_maps feature.
   */
  public static final String EXCLUDE_PRIVATE_HEADERS_IN_MODULE_MAPS =
      "exclude_private_headers_in_module_maps";

  /**
   * A string constant for the use_header_modules feature.
   *
   * <p>This feature is only used during rollout; we expect to default enable this once we
   * have verified that module-enabled compilation is stable enough.
   */
  public static final String USE_HEADER_MODULES = "use_header_modules";

  /**
   * A string constant for the generate_submodules feature.
   *
   * <p>This feature is only used temporarily to make the switch to using submodules easier. With
   * submodules, each header of a cc_library is placed into a submodule of the module generated for
   * the appropriate target. As this influences the layering_check semantics and needs to be synced
   * with a clang release, we want to be able to switch back and forth easily.
   */
  public static final String GENERATE_SUBMODULES = "generate_submodules";

  /**
   * A string constant for the only_doth_headers_in_module_maps.
   *
   * <p>This feature filters any headers without a ".h" suffix from generated module maps.
   */
  public static final String ONLY_DOTH_HEADERS_IN_MODULE_MAPS = "only_doth_headers_in_module_maps";

  /**
   * A string constant for the no_legacy_features feature.
   *
   * <p>If this feature is enabled, Bazel will not extend the crosstool configuration with the
   * default legacy feature set.
   */
  public static final String NO_LEGACY_FEATURES = "no_legacy_features";

  /**
   * A string constant for the feature that makes us build per-object debug info files.
   */
  public static final String PER_OBJECT_DEBUG_INFO = "per_object_debug_info";

  /**
   * A string constant for the PIC feature.
   *
   * <p>If this feature is active (currently it cannot be switched off) and PIC compilation is
   * requested, the "pic" build variable will be defined with an empty string as its value.
   */
  public static final String PIC = "pic";

  /**
   * A string constant for the feature the represents preprocessor defines.
   */
  public static final String PREPROCESSOR_DEFINES = "preprocessor_defines";

  /**
   * A string constant for the include_paths feature.
   */
  public static final String INCLUDE_PATHS = "include_paths";

  /**
   * A string constant for the ThinLTO feature.
   */
  public static final String THIN_LTO = "thin_lto";

  /**
   * A string constant for the PDB file generation feature, should only be used for toolchains
   * targeting Windows that include a linker producing PDB files
   */
  public static final String GENERATE_PDB_FILE = "generate_pdb_file";

  /**
   * A string constant for no_stripping feature, if it's specified, then no strip action config is
   * needed, instead the stripped binary will simply be a symlink (or a copy on Windows) of the
   * original binary.
   */
  public static final String NO_STRIPPING = "no_stripping";

  /** A string constant for /showIncludes parsing feature, should only be used for MSVC toolchain */
  public static final String PARSE_SHOWINCLUDES = "parse_showincludes";

  /*
   * A string constant for the fdo_instrument feature.
   */
  public static final String FDO_INSTRUMENT = "fdo_instrument";

  /**
   * A string constant for the fdo_optimize feature.
   */
  public static final String FDO_OPTIMIZE = "fdo_optimize";

  /**
   * A string constant for the autofdo feature.
   */
  public static final String AUTOFDO = "autofdo";

  /**
   * A string constant for the lipo feature.
   */
  public static final String LIPO = "lipo";

  /**
   * A string constant for the coverage feature.
   */
  public static final String COVERAGE = "coverage";

  /** Produce artifacts for coverage in llvm coverage mapping format. */
  public static final String LLVM_COVERAGE_MAP_FORMAT = "llvm_coverage_map_format";

  /** Produce artifacts for coverage in gcc coverage mapping format. */
  public static final String GCC_COVERAGE_MAP_FORMAT = "gcc_coverage_map_format";

  /** A string constant for the match-clif feature. */
  public static final String MATCH_CLIF = "match_clif";

  /** A string constant for the contains_objc_source feature. */
  public static final String CONTAINS_OBJC_SOURCE = "contains_objc_source";
}
