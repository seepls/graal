/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.image;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.code.DataSection;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.options.Option;

import com.oracle.objectfile.ObjectFile;
import com.oracle.svm.core.code.FrameInfoEncoder;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.deopt.DeoptEntryInfopoint;
import com.oracle.svm.core.graal.code.SubstrateDataBuilder;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.code.CompilationInfo;
import com.oracle.svm.hosted.code.CompilationInfoSupport;
import com.oracle.svm.hosted.image.NativeBootImage.NativeTextSectionImpl;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;

import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.ConstantReference;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public abstract class NativeImageCodeCache {

    public static class Options {
        @Option(help = "Verify that all possible deoptimization entry points have been properly compiled and registered in the metadata")//
        public static final HostedOptionKey<Boolean> VerifyDeoptimizationEntryPoints = new HostedOptionKey<>(false);
    }

    final NativeImageHeap imageHeap;

    final Map<HostedMethod, CompilationResult> compilations;

    private final DataSection dataSection;

    private final Map<JavaConstant, String> constantReasons = new HashMap<>();

    public NativeImageCodeCache(Map<HostedMethod, CompilationResult> compilations, NativeImageHeap imageHeap) {
        this.compilations = compilations;
        this.imageHeap = imageHeap;
        this.dataSection = new DataSection();
    }

    public abstract int getCodeCacheSize();

    public abstract void layout(DebugContext debug);

    protected void layoutConstants() {
        for (CompilationResult compilation : compilations.values()) {
            for (DataSection.Data data : compilation.getDataSection()) {
                if (data instanceof SubstrateDataBuilder.ObjectData) {
                    JavaConstant constant = ((SubstrateDataBuilder.ObjectData) data).getConstant();
                    constantReasons.put(constant, compilation.getName());
                }
            }

            dataSection.addAll(compilation.getDataSection());
        }
        dataSection.close();
    }

    public void addConstantsToHeap() {
        for (DataSection.Data data : dataSection) {
            if (data instanceof SubstrateDataBuilder.ObjectData) {
                JavaConstant constant = ((SubstrateDataBuilder.ObjectData) data).getConstant();
                addConstantToHeap(constant);
            }
        }
        for (CompilationResult compilationResult : compilations.values()) {
            for (DataPatch patch : compilationResult.getDataPatches()) {
                if (patch.reference instanceof ConstantReference) {
                    addConstantToHeap(((ConstantReference) patch.reference).getConstant());
                }
            }
        }
    }

    private void addConstantToHeap(Constant constant) {
        Object obj = SubstrateObjectConstant.asObject(constant);

        if (!imageHeap.getMetaAccess().lookupJavaType(obj.getClass()).getWrapped().isInstantiated()) {
            throw shouldNotReachHere("Non-instantiated type referenced by a compiled method: " + obj.getClass().getName());
        }

        imageHeap.addObject(obj, false, constantReasons.get(constant));
    }

    protected int getConstantsSize() {
        return dataSection.getSectionSize();
    }

    public int getAlignedConstantsSize() {
        return ConfigurationValues.getObjectLayout().alignUp(getConstantsSize());
    }

    /*
     * Constants and code objects are all assigned offsets in the heap. Reference constants can
     * refer to other heap objects. TODO: is it true that that all code-->data references go via a
     * Constant? It appears so, but I'm not sure. -srk
     */

    public abstract void patchMethods(RelocatableBuffer relocs, ObjectFile objectFile);

    public abstract void writeCode(RelocatableBuffer buffer);

    public void writeConstants(RelocatableBuffer buffer) {
        ByteBuffer bb = buffer.getBuffer();
        dataSection.buildDataSection(bb, (position, constant) -> {
            imageHeap.writeReference(buffer, position, SubstrateObjectConstant.asObject(constant), "VMConstant: " + constant);
        });
    }

    public abstract NativeTextSectionImpl getTextSectionImpl(RelocatableBuffer buffer, ObjectFile objectFile, NativeImageCodeCache codeCache);

    public abstract String[] getCCInputFiles(Path tempDirectory, String imageName);

    public Map<HostedMethod, CompilationResult> getCompilations() {
        return compilations;
    }

    public void printCompilationResults() {
        System.out.println("--- compiled methods");
        for (Entry<HostedMethod, CompilationResult> entry : compilations.entrySet()) {
            printCompilationResult(entry.getKey(), entry.getValue());
        }
        System.out.println("--- vtables:");
        for (HostedType type : imageHeap.getUniverse().getTypes()) {
            for (int i = 0; i < type.getVTable().length; i++) {
                HostedMethod method = type.getVTable()[i];
                if (method != null) {
                    CompilationResult comp = compilations.get(type.getVTable()[i]);
                    if (comp != null) {
                        System.out.format("%d %s @ %d: %s = 0x%x\n", type.getTypeID(), type.toJavaName(false), i, method.format("%r %n(%p)"), method.getCodeAddressOffset());
                    }
                }
            }
        }
    }

    protected abstract void printCompilationResult(HostedMethod method, CompilationResult compilationResult);

    protected static class FrameInfoCustomization extends FrameInfoEncoder.NamesFromMethod {
        int numDeoptEntryPoints;
        int numDuringCallEntryPoints;

        @Override
        protected Class<?> getDeclaringJavaClass(ResolvedJavaMethod method) {
            HostedType type = (HostedType) method.getDeclaringClass();
            assert type.getWrapped().isInTypeCheck() : "Declaring class not marked as used, therefore the DynamicHub is not initialized properly: " + method.format("%H.%n(%p)");
            return type.getJavaClass();
        }

        @Override
        protected boolean shouldStoreMethod() {
            return false;
        }

        @Override
        protected boolean shouldInclude(ResolvedJavaMethod method, Infopoint infopoint) {
            CompilationInfo compilationInfo = ((HostedMethod) method).compilationInfo;
            BytecodeFrame topFrame = infopoint.debugInfo.frame();

            if (isDeoptEntry(method, infopoint)) {
                /* Collect number of entry points for later printing of statistics. */
                if (infopoint instanceof DeoptEntryInfopoint) {
                    numDeoptEntryPoints++;
                } else if (infopoint instanceof Call) {
                    numDuringCallEntryPoints++;
                } else {
                    throw shouldNotReachHere();
                }

                return true;
            }
            BytecodeFrame rootFrame = topFrame;
            while (rootFrame.caller() != null) {
                rootFrame = rootFrame.caller();
            }
            assert rootFrame.getMethod().equals(method);

            boolean isDeoptEntry = compilationInfo.isDeoptEntry(rootFrame.getBCI(), rootFrame.duringCall, rootFrame.rethrowException);
            if (infopoint instanceof DeoptEntryInfopoint) {
                assert isDeoptEntry;
                assert topFrame == rootFrame : "Deoptimization target has inlined frame";

                numDeoptEntryPoints++;
                return true;

            }

            if (isDeoptEntry && topFrame.duringCall) {
                assert infopoint instanceof Call;
                assert topFrame == rootFrame : "Deoptimization target has inlined frame";

                numDuringCallEntryPoints++;
                return true;
            }

            for (BytecodeFrame frame = topFrame; frame != null; frame = frame.caller()) {
                if (CompilationInfoSupport.singleton().isFrameInformationRequired(frame.getMethod())) {
                    /*
                     * Somewhere in the inlining hierarchy is a method for which frame information
                     * was explicitly requested. For simplicity, we output frame information for all
                     * methods in the inlining chain.
                     *
                     * We require frame information, for example, for frames that must be visible to
                     * SubstrateStackIntrospection.
                     */
                    return true;
                }
            }

            if (compilationInfo.canDeoptForTesting()) {
                return true;
            }

            return false;
        }

        @Override
        protected boolean isDeoptEntry(ResolvedJavaMethod method, Infopoint infopoint) {
            CompilationInfo compilationInfo = ((HostedMethod) method).compilationInfo;
            BytecodeFrame topFrame = infopoint.debugInfo.frame();

            BytecodeFrame rootFrame = topFrame;
            while (rootFrame.caller() != null) {
                rootFrame = rootFrame.caller();
            }
            assert rootFrame.getMethod().equals(method);

            boolean isDeoptEntry = compilationInfo.isDeoptEntry(rootFrame.getBCI(), rootFrame.duringCall, rootFrame.rethrowException);
            if (infopoint instanceof DeoptEntryInfopoint) {
                assert isDeoptEntry;
                assert topFrame == rootFrame : "Deoptimization target has inlined frame";
                return true;
            }
            if (isDeoptEntry && topFrame.duringCall) {
                assert infopoint instanceof Call;
                assert topFrame == rootFrame : "Deoptimization target has inlined frame";
                return true;
            }
            return false;
        }
    }
}
