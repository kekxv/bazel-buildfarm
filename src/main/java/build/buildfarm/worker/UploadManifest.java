// Copyright 2018 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker;

import static build.buildfarm.common.io.Utils.readdir;
import static build.buildfarm.worker.Utils.statIfFound;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.Tree;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.io.Dirent;
import build.buildfarm.common.io.FileStatus;
import build.buildfarm.instance.stub.Chunker;
import build.buildfarm.v1test.CASInsertionPolicy;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UploadManifest adds output metadata to a {@link ActionResult}. */
/** FIXME move into worker implementation and implement 'fast add' with this for sharding */
public class UploadManifest {
  private final DigestUtil digestUtil;
  private final ActionResult.Builder result;
  private final Path execRoot;
  private final FileStore fileStore;
  private final boolean allowSymlinks;
  private final int inlineContentLimit;
  private final Map<Digest, Path> digestToFile;
  private final Map<Digest, Chunker> digestToChunkers;
  private transient int inlineContentBytes;

  /**
   * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
   * builder is populated through a call to {@link #addFile(Digest, Path)}.
   */
  public UploadManifest(
      DigestUtil digestUtil,
      ActionResult.Builder result,
      Path execRoot,
      boolean allowSymlinks,
      int inlineContentLimit)
      throws IOException {
    this.digestUtil = digestUtil;
    this.result = result;
    this.execRoot = execRoot;
    this.allowSymlinks = allowSymlinks;
    this.inlineContentLimit = inlineContentLimit;

    this.digestToFile = new HashMap<>();
    this.digestToChunkers = new HashMap<>();
    this.inlineContentBytes = 0;

    fileStore = Files.getFileStore(execRoot);
  }

  /** Add a collection of files to the UploadManifest. */
  public void addFiles(Iterable<Path> files, CASInsertionPolicy policy)
      throws IllegalStateException, IOException, InterruptedException {
    for (Path file : files) {
      FileStatus stat = statIfFound(file, /* followSymlinks= */ false, fileStore);
      if (stat == null) {
        // We ignore requested results that have not been generated by the action.
        continue;
      }
      if (stat.isDirectory()) {
        mismatchedOutput(file);
      } else if (stat.isFile()) {
        addFile(file, policy);
      } else if (allowSymlinks && stat.isSymbolicLink()) {
        /** FIXME symlink to directory? */
        // is the stat correct?
        addFile(file, policy);
      } else {
        illegalOutput(file);
      }
    }
  }

  /**
   * Add a collection of directories to the UploadManifest. Adding a directory has the effect of 1)
   * uploading a {@link Tree} protobuf message from which the whole structure of the directory,
   * including the descendants, can be reconstructed and 2) uploading all the non-directory
   * descendant files.
   */
  public void addDirectories(Iterable<Path> dirs)
      throws IllegalStateException, IOException, InterruptedException {
    for (Path dir : dirs) {
      FileStatus stat = statIfFound(dir, /* followSymlinks= */ false, fileStore);
      if (stat == null) {
        // We ignore requested results that have not been generated by the action.
        continue;
      }
      if (stat.isDirectory()) {
        addDirectory(dir);
      } else if (stat.isFile() || stat.isSymbolicLink()) {
        mismatchedOutput(dir);
      } else {
        illegalOutput(dir);
      }
    }
  }

  /** Map of digests to file paths to upload. */
  public Map<Digest, Path> getDigestToFile() {
    return digestToFile;
  }

  /**
   * Map of digests to chunkers to upload. When the file is a regular, non-directory file it is
   * transmitted through {@link #getDigestToFile()}. When it is a directory, it is transmitted as a
   * {@link Tree} protobuf message through {@link #getDigestToChunkers()}.
   */
  public Map<Digest, Chunker> getDigestToChunkers() {
    return digestToChunkers;
  }

  public void addContent(
      ByteString content,
      CASInsertionPolicy policy,
      Consumer<ByteString> setRaw,
      Consumer<Digest> setDigest) {
    boolean withinLimit = inlineContentBytes + content.size() <= inlineContentLimit;
    if (withinLimit) {
      setRaw.accept(content);
      inlineContentBytes += content.size();
    } else {
      setRaw.accept(ByteString.EMPTY);
    }
    if (policy.equals(CASInsertionPolicy.ALWAYS_INSERT)
        || (!withinLimit && policy.equals(CASInsertionPolicy.INSERT_ABOVE_LIMIT))) {
      Digest digest = digestUtil.compute(content);
      setDigest.accept(digest);
      Chunker chunker = Chunker.builder().setInput(content).build();
      digestToChunkers.put(digest, chunker);
    }
  }

  private void addFile(Path file, CASInsertionPolicy policy) throws IOException {
    Digest digest = digestUtil.compute(file);
    result
        .addOutputFilesBuilder()
        .setPath(execRoot.relativize(file).toString())
        .setIsExecutable(Files.isExecutable(file))
        .setDigest(digest);
    digestToFile.put(digest, file);
  }

  private void addDirectory(Path dir) throws IllegalStateException, IOException {
    Tree.Builder tree = Tree.newBuilder();
    Directory root = computeDirectory(dir, tree);
    tree.setRoot(root);

    ByteString blob = tree.build().toByteString();
    Digest digest = digestUtil.compute(blob);
    Chunker chunker = Chunker.builder().setInput(blob).build();

    if (result != null) {
      result
          .addOutputDirectoriesBuilder()
          .setPath(execRoot.relativize(dir).toString())
          .setTreeDigest(digest);
    }

    digestToChunkers.put(digest, chunker);
  }

  private Directory computeDirectory(Path path, Tree.Builder tree)
      throws IllegalStateException, IOException {
    Directory.Builder b = Directory.newBuilder();

    List<Dirent> sortedDirent = readdir(path, /* followSymlinks= */ false, fileStore);
    sortedDirent.sort(Comparator.comparing(Dirent::getName));

    for (Dirent dirent : sortedDirent) {
      String name = dirent.getName();
      Path child = path.resolve(name);
      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        Directory dir = computeDirectory(child, tree);
        b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir));
        tree.addChildren(dir);
      } else if (dirent.getType() == Dirent.Type.FILE
          || (dirent.getType() == Dirent.Type.SYMLINK && allowSymlinks)) {
        Digest digest = digestUtil.compute(child);
        b.addFilesBuilder()
            .setName(name)
            .setDigest(digest)
            .setIsExecutable(Files.isExecutable(child));
        digestToFile.put(digest, child);
      } else {
        illegalOutput(child);
      }
    }

    return b.build();
  }

  private void mismatchedOutput(Path what) throws IllegalStateException, IOException {
    String kind =
        Files.isSymbolicLink(what)
            ? "symbolic link"
            : Files.isDirectory(what) ? "directory" : "file";
    String expected = kind.equals("directory") ? "file" : "directory";
    throw new IllegalStateException(
        String.format(
            "Output %s is a %s. It was expected to be a %s.",
            execRoot.relativize(what), kind, expected));
  }

  private void illegalOutput(Path what) throws IllegalStateException, IOException {
    String kind = Files.isSymbolicLink(what) ? "symbolic link" : "special file";
    throw new IllegalStateException(
        String.format(
            "Output %s is a %s. Only regular files and directories may be "
                + "uploaded to a remote cache. "
                + "Change the file type or use --remote_allow_symlink_upload.",
            execRoot.relativize(what), kind));
  }
}
