/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform

import com.android.SdkConstants
import com.android.annotations.NonNull
import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.dependency.level2.AndroidDependency
import com.android.builder.model.AndroidProject
import com.android.utils.FileUtils
import com.android.utils.StringHelper
import com.google.common.base.Joiner
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import com.house365.build.ShadeTaskManager
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException

import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A transform that merges all the incoming inputs (folders and jars) into a single jar in a
 * single combined output.
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeJniLibsTransform extends Transform {

    private BaseVariant variant
    private LibraryExtension libraryExtension
    private boolean isLibrary = true;
    private Project project
    private VariantScope variantScope
    private static logger = org.slf4j.LoggerFactory.getLogger(ShadeJniLibsTransform.class)
    private File jniLibsFolder
    private ShadeTaskManager shadeTaskManager


    public ShadeJniLibsTransform(Project project, BaseExtension LibraryExtension, ShadeTaskManager shadeTaskManager) {
        this.shadeTaskManager = shadeTaskManager
        this.project = project
        this.libraryExtension = LibraryExtension
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeJniLibs";
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @NonNull
    @Override
    public Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_NATIVE_LIBS;
    }

    @NonNull
    @Override
    public Set<Scope> getScopes() {
        return TransformManager.EMPTY_SCOPES;
    }

    @NonNull
    @Override
    public Set<Scope> getReferencedScopes() {
        return TransformManager.SCOPE_FULL_LIBRARY;
    }

    public void transform(@NonNull TransformInvocation invocation)
            throws TransformException, InterruptedException, IOException {
        this.variant = ShadeAarClassTransform.getCurrentVariant(libraryExtension, invocation)
        if (!(variant instanceof LibraryVariant)) {
            return
        }

        variantScope = variant.variantData.getScope()
        File outRootFolder = FileUtils.join(variantScope.getGlobalScope().getBuildDir(), StringHelper.toStrings(
                AndroidProject.FD_INTERMEDIATES,
                "transforms",
                getName(),
                variantScope.getDirectorySegments()));
        jniLibsFolder = IntermediateFolderUtils.getContentLocation(outRootFolder, "jni", getOutputTypes(), Sets.immutableEnumSet(Scope.PROJECT), Format.DIRECTORY)
        isLibrary = this.variantScope.getVariantData() instanceof LibraryVariantData;
        if (!isLibrary)
            throw new ProjectConfigurationException("The shade plugin only be used for android library.", null)
        List<AndroidDependency> libraryDependencies = shadeTaskManager.getVariantShadeLibraries(variant.getVariantData().getName())
        for (AndroidDependency dependency : libraryDependencies) {
            copyFromFolder(dependency.getJniFolder());
        }
        for (TransformInput input : invocation.getInputs()) {
            for (JarInput jarInput : input.getJarInputs()) {
                copyFromJar(jarInput.getFile());
            }

            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                copyFromFolder(directoryInput.getFile());
            }
        };
    }


    private void copyFromJar(@NonNull File jarFile) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ZipFile zipFile = new ZipFile(jarFile);
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                String entryPath = entry.getName();

                if (!pattern.matcher(entryPath).matches()) {
                    continue;
                }

                // read the content.
                buffer.reset();
                ByteStreams.copy(zipFile.getInputStream(entry), buffer);

                // get the output file and write to it.
                Files.write(buffer.toByteArray(), computeFile(jniLibsFolder, entryPath));
            }
        } finally {
            zipFile.close();
        }
    }
    /**
     * computes a file path from a root folder and a zip archive path.
     * @param rootFolder the root folder
     * @param path the archive path
     * @return the File
     */
    private static File computeFile(@NonNull File rootFolder, @NonNull String path) {
        path = FileUtils.toSystemDependentPath(path);
        return new File(rootFolder, path);
    }

    private void copyFromFolder(@NonNull File rootDirectory) throws IOException {
        copyFromFolder(rootDirectory, Lists.<String> newArrayListWithCapacity(2));
    }

    private final Pattern pattern = Pattern.compile("[^/]+/[^/]+\\.so");

    private void copyFromFolder(@NonNull File from, @NonNull List<String> pathSegments)
            throws IOException {
        File[] children = from.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String name) {
                return file.isDirectory() || name.endsWith(SdkConstants.DOT_NATIVE_LIBS);
            }
        });

        if (children != null) {
            for (File child : children) {
                pathSegments.add(child.getName());
                if (child.isDirectory()) {
                    copyFromFolder(child, pathSegments);
                } else if (child.isFile()) {
                    if (pathSegments.size() == 3)
                        pathSegments = pathSegments.subList(1, 3)
                    String join = Joiner.on('/').join(pathSegments)
                    if (pattern.matcher(join).matches()) {
                        // copy the file. However we do want to skip the first segment ('lib') here
                        // since the 'jni' folder is representing the same concept.
                        File to = FileUtils.join(jniLibsFolder, pathSegments);
                        FileUtils.mkdirs(to.getParentFile());
                        Files.copy(child, to);
                    }
                }

                pathSegments.remove(pathSegments.size() - 1);
            }
        }
    }
}

