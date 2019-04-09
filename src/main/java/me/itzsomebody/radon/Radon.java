/*
 * Radon - An open-source Java obfuscator
 * Copyright (C) 2019 ItzSomebody
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package me.itzsomebody.radon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import me.itzsomebody.radon.asm.ClassTree;
import me.itzsomebody.radon.asm.ClassWrapper;
import me.itzsomebody.radon.exceptions.MissingClassException;
import me.itzsomebody.radon.exceptions.RadonException;
import me.itzsomebody.radon.transformers.Transformer;
import me.itzsomebody.radon.transformers.miscellaneous.TrashClasses;
import me.itzsomebody.radon.utils.FileUtils;
import me.itzsomebody.radon.utils.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * This class is how Radon processes the provided {@link ObfuscationConfiguration} to produce an obfuscated jar.
 *
 * @author ItzSomebody
 */
public class Radon {
    public ObfuscationConfiguration config;
    private Map<String, ClassTree> hierarchy = new HashMap<>();
    public Map<String, ClassWrapper> classes = new HashMap<>();
    public Map<String, ClassWrapper> classPath = new HashMap<>();
    public Map<String, byte[]> resources = new HashMap<>();

    public Radon(ObfuscationConfiguration config) {
        this.config = config;
    }

    /**
     * Execution order. Feel free to modify.
     */
    public void run() {
        loadClassPath();
        loadInput();
        buildInheritance();

        if (config.getnTrashClasses() > 0)
            config.getTransformers().add(0, new TrashClasses());
        if (config.getTransformers().isEmpty())
            throw new RadonException("No transformers are enabled.");

        Transformer.sort(config.getTransformers());

        Logger.stdOut("------------------------------------------------");
        this.config.getTransformers().stream().filter(Objects::nonNull).forEach(transformer -> {
            long current = System.currentTimeMillis();
            Logger.stdOut(String.format("Running %s transformer.", transformer.getName()));
            transformer.init(this);
            transformer.transform();
            Logger.stdOut(String.format("Finished running %s transformer. [%dms]", transformer.getName(), (System.currentTimeMillis() - current)));
            Logger.stdOut("------------------------------------------------");
        });

        writeOutput();
    }

    private void writeOutput() {
        File output = config.getOutput();
        Logger.stdOut(String.format("Writing output to \"%s\".", output.getAbsolutePath()));

        if (output.exists())
            Logger.stdOut(String.format("Output file already exists, renamed to %s.", FileUtils.renameExistingFile(output)));

        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output));
            zos.setLevel(config.getCompressionLevel());

            this.classes.values().forEach(classWrapper -> {
                try {
                    ZipEntry entry = new ZipEntry(classWrapper.classNode.name + ".class");

                    ClassWriter cw = new CustomClassWriter(ClassWriter.COMPUTE_FRAMES);
                    cw.newUTF8("RADON" + Main.VERSION);
                    classWrapper.strConsts.forEach(cw::newUTF8);

                    try {
                        classWrapper.classNode.accept(cw);
                    } catch (Throwable t) {
                        Logger.stdErr(String.format("Error writing class %s. Skipping frames (might cause runtime errors).", classWrapper.classNode.name + ".class"));
                        t.printStackTrace();

                        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        cw.newUTF8("RADON" + Main.VERSION);
                        classWrapper.strConsts.forEach(cw::newUTF8);

                        classWrapper.classNode.accept(cw);
                    }

                    zos.putNextEntry(entry);
                    zos.write(cw.toByteArray());
                    zos.closeEntry();
                } catch (Throwable t) {
                    Logger.stdErr(String.format("Error writing class %s. Skipping.", classWrapper.classNode.name + ".class"));
                    t.printStackTrace();
                }
            });

            this.resources.forEach((name, bytes) -> {
                try {
                    ZipEntry entry = new ZipEntry(name);

                    zos.putNextEntry(entry);
                    zos.write(bytes);
                    zos.closeEntry();
                } catch (IOException ioe) {
                    Logger.stdErr(String.format("Error writing resource %s. Skipping.", name));
                    ioe.printStackTrace();
                }
            });

            zos.setComment(Main.ATTRIBUTION);
            zos.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new RadonException();
        }
    }

    private void loadClassPath() {
        config.getLibraries().forEach(file -> {
            if (file.exists()) {
                Logger.stdOut(String.format("Loading library \"%s\".", file.getAbsolutePath()));

                try {
                    ZipFile zipFile = new ZipFile(file);
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();

                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();

                        if (!entry.isDirectory() && entry.getName().endsWith(".class"))
                            try {
                                ClassReader cr = new ClassReader(zipFile.getInputStream(entry));
                                ClassNode classNode = new ClassNode();
                                cr.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                                ClassWrapper classWrapper = new ClassWrapper(classNode, true);

                                this.classPath.put(classWrapper.originalName, classWrapper);
                            } catch (Throwable t) {
                                Logger.stdErr(String.format("Error while loading library class \"%s\".", entry.getName().replace(".class", "")));
                                t.printStackTrace();
                            }
                    }
                } catch (ZipException e) {
                    Logger.stdErr(String.format("Library \"%s\" could not be opened as a zip file.", file.getAbsolutePath()));
                    e.printStackTrace();
                } catch (IOException e) {
                    Logger.stdErr(String.format("IOException happened while trying to load classes from \"%s\".", file.getAbsolutePath()));
                    e.printStackTrace();
                }
            } else
                Logger.stdWarn(String.format("Library \"%s\" could not be found and will be ignored.", file.getAbsolutePath()));
        });
    }

    private void loadInput() {
        File input = this.config.getInput();

        if (input.exists()) {
            Logger.stdOut(String.format("Loading input \"%s\".", input.getAbsolutePath()));

            try {
                ZipFile zipFile = new ZipFile(input);
                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();

                    if (!entry.isDirectory())
                        if (entry.getName().endsWith(".class"))
                            try {
                                ClassReader cr = new ClassReader(zipFile.getInputStream(entry));
                                ClassNode classNode = new ClassNode();
                                cr.accept(classNode, ClassReader.SKIP_FRAMES);
                                if (classNode.version <= Opcodes.V1_5)
                                    for (int i = 0; i < classNode.methods.size(); i++) {
                                        MethodNode methodNode = classNode.methods.get(i);
                                        JSRInlinerAdapter adapter = new JSRInlinerAdapter(methodNode, methodNode.access, methodNode.name, methodNode.desc, methodNode.signature, methodNode.exceptions.toArray(new String[0]));
                                        methodNode.accept(adapter);
                                        classNode.methods.set(i, adapter);
                                    }

                                ClassWrapper classWrapper = new ClassWrapper(classNode, false);

                                this.classPath.put(classWrapper.originalName, classWrapper);
                                this.classes.put(classWrapper.originalName, classWrapper);
                            } catch (Throwable t) {
                                Logger.stdWarn(String.format("Could not load %s as a class.", entry.getName()));
                                this.resources.put(entry.getName(), IOUtils.toByteArray(zipFile.getInputStream(entry)));
                            }
                        else
                            this.resources.put(entry.getName(), IOUtils.toByteArray(zipFile.getInputStream(entry)));
                }
            } catch (ZipException e) {
                Logger.stdErr(String.format("Input file \"%s\" could not be opened as a zip file.", input.getAbsolutePath()));
                e.printStackTrace();
                throw new RadonException(e);
            } catch (IOException e) {
                Logger.stdErr(String.format("IOException happened while trying to load classes from \"%s\".", input.getAbsolutePath()));
                e.printStackTrace();
                throw new RadonException(e);
            }
        } else {
            Logger.stdErr(String.format("Unable to find \"%s\".", input.getAbsolutePath()));
            throw new RadonException();
        }
    }

    public byte[] class2Bytes(ClassWrapper wrapper) {
        ClassWriter cw = new CustomClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.newUTF8("RADON" + Main.VERSION);
        wrapper.classNode.accept(cw);
        wrapper.strConsts.forEach(cw::newUTF8);

        return cw.toByteArray();
    }

    public ClassTree getTree(String ref) {
        if (!hierarchy.containsKey(ref)) {
            ClassWrapper wrapper = classPath.get(ref);
            buildHierarchy(wrapper, null);
        }

        return hierarchy.get(ref);
    }

    private void buildHierarchy(ClassWrapper classWrapper, ClassWrapper sub) {
        if (hierarchy.get(classWrapper.classNode.name) == null) {
            ClassTree tree = new ClassTree(classWrapper);

            if (classWrapper.classNode.superName != null) {
                tree.parentClasses.add(classWrapper.classNode.superName);

                ClassWrapper superClass = classPath.get(classWrapper.classNode.superName);
                if (superClass == null)
                    throw new RadonException(classWrapper.classNode.superName + " is missing in the classpath.");

                buildHierarchy(superClass, classWrapper);
            }
            if (classWrapper.classNode.interfaces != null)
                classWrapper.classNode.interfaces.forEach(s -> {
                    tree.parentClasses.add(s);

                    ClassWrapper interfaceClass = classPath.get(s);
                    if (interfaceClass == null)
                        throw new RadonException(s + " is missing in the classpath.");

                    buildHierarchy(interfaceClass, classWrapper);
                });
            hierarchy.put(classWrapper.classNode.name, tree);
        }

        if (sub != null)
            hierarchy.get(classWrapper.classNode.name).subClasses.add(sub.classNode.name);
    }

    private void buildInheritance() {
        classes.values().forEach(classWrapper -> buildHierarchy(classWrapper, null));
    }

    private ClassNode returnClazz(String ref) {
        ClassWrapper clazz = classPath.get(ref);

        if (clazz == null)
            throw new MissingClassException(ref + " does not exist in classpath!");

        return clazz.classNode;
    }

    public boolean isAssignableFrom(String type1, String type2) {
        if ("java/lang/Object".equals(type1))
            return true;
        if (type1.equals(type2))
            return true;

        returnClazz(type1);
        returnClazz(type2);

        ClassTree firstTree = getTree(type1);
        if (firstTree == null)
            throw new MissingClassException("Could not find " + type1 + " in the built class hierarchy");

        Set<String> allChildren = new HashSet<>();
        Deque<String> toProcess = new ArrayDeque<>(firstTree.subClasses);
        while (!toProcess.isEmpty()) {
            String s = toProcess.poll();

            if (allChildren.add(s)) {
                returnClazz(s);
                ClassTree tempTree = getTree(s);
                toProcess.addAll(tempTree.subClasses);
            }
        }
        return allChildren.contains(type2);
    }

    class CustomClassWriter extends ClassWriter {
        private CustomClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2) {
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2))
                return "java/lang/Object";

            String first = deriveCommonSuperName(type1, type2);
            String second = deriveCommonSuperName(type2, type1);
            if (!"java/lang/Object".equals(first))
                return first;

            if (!"java/lang/Object".equals(second))
                return second;

            return getCommonSuperClass(returnClazz(type1).superName, returnClazz(type2).superName);
        }

        private String deriveCommonSuperName(String type1, String type2) {
            ClassNode first = returnClazz(type1);
            ClassNode second = returnClazz(type2);
            if (isAssignableFrom(type1, type2))
                return type1;
            else if (isAssignableFrom(type2, type1))
                return type2;
            else if (Modifier.isInterface(first.access) || Modifier.isInterface(second.access))
                return "java/lang/Object";
            else {
                String temp;

                do {
                    temp = first.superName;
                    first = returnClazz(temp);
                } while (!isAssignableFrom(temp, type2));
                return temp;
            }
        }
    }
}
