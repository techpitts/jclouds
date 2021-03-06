/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.blobstore;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.getCausalChain;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.find;
import static com.google.common.collect.Iterables.size;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.filter;
import static com.google.common.collect.Sets.newTreeSet;
import static com.google.common.io.ByteStreams.toByteArray;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriBuilder;

import org.jclouds.Constants;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.Blob.Factory;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.jclouds.blobstore.domain.MutableStorageMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.functions.HttpGetOptionsListToGetOptions;
import org.jclouds.blobstore.internal.BaseAsyncBlobStore;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.blobstore.strategy.IfDirectoryReturnNameStrategy;
import org.jclouds.blobstore.util.BlobUtils;
import org.jclouds.collect.Memoized;
import org.jclouds.crypto.Crypto;
import org.jclouds.crypto.CryptoStreams;
import org.jclouds.date.DateService;
import org.jclouds.domain.Location;
import org.jclouds.http.HttpCommand;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.HttpResponseException;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.options.HttpRequestOptions;
import org.jclouds.io.ContentMetadata;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;
import org.jclouds.io.Payloads;
import org.jclouds.io.payloads.ByteArrayPayload;
import org.jclouds.io.payloads.DelegatingPayload;
import org.jclouds.javax.annotation.Nullable;
import org.jclouds.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Implementation of {@link BaseAsyncBlobStore} which keeps all data in a local Map object.
 * 
 * @author Adrian Cole
 * @author James Murty
 */
public class TransientAsyncBlobStore extends BaseAsyncBlobStore {

   @Resource
   protected Logger logger = Logger.NULL;

   protected final DateService dateService;
   protected final Crypto crypto;
   protected final ConcurrentMap<String, ConcurrentMap<String, Blob>> containerToBlobs;
   protected final Provider<UriBuilder> uriBuilders;
   protected final ConcurrentMap<String, Location> containerToLocation;
   protected final HttpGetOptionsListToGetOptions httpGetOptionsConverter;
   protected final IfDirectoryReturnNameStrategy ifDirectoryReturnName;
   protected final Factory blobFactory;
   protected final TransientStorageStrategy storageStrategy;

   @Inject
   protected TransientAsyncBlobStore(BlobStoreContext context,
         DateService dateService, Crypto crypto,
         HttpGetOptionsListToGetOptions httpGetOptionsConverter,
         IfDirectoryReturnNameStrategy ifDirectoryReturnName,
         BlobUtils blobUtils,
         @Named(Constants.PROPERTY_USER_THREADS) ExecutorService service,
         Supplier<Location> defaultLocation,
         @Memoized Supplier<Set<? extends Location>> locations,
         Factory blobFactory,
         ConcurrentMap<String, ConcurrentMap<String, Blob>> containerToBlobs, Provider<UriBuilder> uriBuilders,
         ConcurrentMap<String, Location> containerToLocation) {
      super(context, blobUtils, service, defaultLocation, locations);
      this.blobFactory = blobFactory;
      this.dateService = dateService;
      this.crypto = crypto;
      this.containerToBlobs = containerToBlobs;
      this.uriBuilders = uriBuilders;
      this.containerToLocation = containerToLocation;
      this.httpGetOptionsConverter = httpGetOptionsConverter;
      this.ifDirectoryReturnName = ifDirectoryReturnName;
      getContainerToLocation().put("stub", defaultLocation.get());
      getContainerToBlobs().put("stub", new ConcurrentHashMap<String, Blob>());
      this.storageStrategy = new TransientStorageStrategy();
   }

   /**
    * default maxResults is 1000
    */
   @Override
   public ListenableFuture<PageSet<? extends StorageMetadata>> list(final String container, ListContainerOptions options) {
      final Map<String, Blob> realContents = getContainerToBlobs().get(container);

      // Check if the container exists
      if (realContents == null)
         return immediateFailedFuture(cnfe(container));

      SortedSet<StorageMetadata> contents = newTreeSet(transform(realContents.keySet(),
            new Function<String, StorageMetadata>() {
               public StorageMetadata apply(String key) {
                  Blob oldBlob = realContents.get(key);
                  checkState(oldBlob != null, "blob " + key + " is not present although it was in the list of "
                        + container);
                  checkState(oldBlob.getMetadata() != null, "blob " + container + "/" + key + " has no metadata");
                  MutableBlobMetadata md = copy(oldBlob.getMetadata());
                  String directoryName = ifDirectoryReturnName.execute(md);
                  if (directoryName != null) {
                     md.setName(directoryName);
                     md.setType(StorageType.RELATIVE_PATH);
                  }
                  return md;
               }
            }));

      String marker = null;
      if (options != null) {
         if (options.getMarker() != null) {
            final String finalMarker = options.getMarker();
            StorageMetadata lastMarkerMetadata = find(contents, new Predicate<StorageMetadata>() {
               public boolean apply(StorageMetadata metadata) {
                  return metadata.getName().compareTo(finalMarker) > 0;
               }
            });
            contents = contents.tailSet(lastMarkerMetadata);
         }

         final String prefix = options.getDir();
         if (prefix != null) {
            contents = newTreeSet(filter(contents, new Predicate<StorageMetadata>() {
               public boolean apply(StorageMetadata o) {
                  return (o != null && o.getName().startsWith(prefix) && !o.getName().equals(prefix));
               }
            }));
         }

         int maxResults = options.getMaxResults() != null ? options.getMaxResults() : 1000;
         if (!contents.isEmpty()) {
            StorageMetadata lastElement = contents.last();
            contents = newTreeSet(Iterables.limit(contents, maxResults));
            if (!contents.contains(lastElement)) {
               // Partial listing
               marker = contents.last().getName();
            }
         }

         final String delimiter = options.isRecursive() ? null : "/";
         if (delimiter != null) {
            SortedSet<String> commonPrefixes = newTreeSet(
                   transform(contents, new CommonPrefixes(prefix, delimiter)));
            commonPrefixes.remove(CommonPrefixes.NO_PREFIX);

            contents = newTreeSet(filter(contents, new DelimiterFilter(prefix, delimiter)));

            Iterables.<StorageMetadata> addAll(contents, transform(commonPrefixes,
                  new Function<String, StorageMetadata>() {
                     public StorageMetadata apply(String o) {
                        MutableStorageMetadata md = new MutableStorageMetadataImpl();
                        md.setType(StorageType.RELATIVE_PATH);
                        md.setName(o);
                        return md;
                     }
                  }));
         }

         // trim metadata, if the response isn't supposed to be detailed.
         if (!options.isDetailed()) {
            for (StorageMetadata md : contents) {
               md.getUserMetadata().clear();
            }
         }
      }

      return Futures.<PageSet<? extends StorageMetadata>> immediateFuture(new PageSetImpl<StorageMetadata>(contents,
            marker));

   }

   private ContainerNotFoundException cnfe(final String name) {
      return new ContainerNotFoundException(name, String.format(
            "container %s not in %s", name,
            storageStrategy.getAllContainerNames()));
   }

   public static MutableBlobMetadata copy(MutableBlobMetadata in) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      ObjectOutput os;
      try {
         os = new ObjectOutputStream(bout);
         os.writeObject(in);
         ObjectInput is = new ObjectInputStream(new ByteArrayInputStream(bout.toByteArray()));
         MutableBlobMetadata metadata = (MutableBlobMetadata) is.readObject();
         convertUserMetadataKeysToLowercase(metadata);
         HttpUtils.copy(in.getContentMetadata(), metadata.getContentMetadata());
         return metadata;
      } catch (Exception e) {
         throw propagate(e);
      }
   }

   private static void convertUserMetadataKeysToLowercase(MutableBlobMetadata metadata) {
      Map<String, String> lowerCaseUserMetadata = newHashMap();
      for (Entry<String, String> entry : metadata.getUserMetadata().entrySet()) {
         lowerCaseUserMetadata.put(entry.getKey().toLowerCase(), entry.getValue());
      }
      metadata.setUserMetadata(lowerCaseUserMetadata);
   }

   public static MutableBlobMetadata copy(MutableBlobMetadata in, String newKey) {
      MutableBlobMetadata newMd = copy(in);
      newMd.setName(newKey);
      return newMd;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Void> removeBlob(final String container, final String key) {
      storageStrategy.removeBlob(container, key);
      return immediateFuture(null);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Void> clearContainer(final String container) {
      getContainerToBlobs().get(container).clear();
      return immediateFuture(null);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Void> deleteContainer(final String container) {
      deleteAndVerifyContainerGone(container);
      return immediateFuture(null);
   }

   public ListenableFuture<Boolean> deleteContainerIfEmpty(final String container) {
      Boolean returnVal = true;
      if (storageStrategy.containerExists(container)) {
         if (getContainerToBlobs().get(container).size() == 0)
            storageStrategy.deleteContainer(container);
         else
            returnVal = false;
      }
      return immediateFuture(returnVal);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Boolean> containerExists(final String containerName) {
      return immediateFuture(storageStrategy.containerExists(containerName));
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<PageSet<? extends StorageMetadata>> list() {
      Iterable<String> containers = storageStrategy.getAllContainerNames();

      return Futures.<PageSet<? extends StorageMetadata>> immediateFuture(new PageSetImpl<StorageMetadata>(transform(
            containers, new Function<String, StorageMetadata>() {
               public StorageMetadata apply(String name) {
                  MutableStorageMetadata cmd = create();
                  cmd.setName(name);
                  cmd.setType(StorageType.CONTAINER);
                  cmd.setLocation(getContainerToLocation().get(name));
                  return cmd;
               }
            }), null));
   }

   protected MutableStorageMetadata create() {
      return new MutableStorageMetadataImpl();
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Boolean> createContainerInLocation(final Location location, final String name) {
      if (storageStrategy.containerExists(name)) {
         return immediateFuture(Boolean.FALSE);
      }
      getContainerToBlobs().put(name, new ConcurrentHashMap<String, Blob>());
      getContainerToLocation().put(name, location != null ? location : defaultLocation.get());
      return immediateFuture(Boolean.TRUE);
   }

   public String getFirstQueryOrNull(String string, @Nullable HttpRequestOptions options) {
      if (options == null)
         return null;
      Collection<String> values = options.buildQueryParameters().get(string);
      return (values != null && values.size() >= 1) ? values.iterator().next() : null;
   }

   protected static class DelimiterFilter implements Predicate<StorageMetadata> {
      private final String prefix;
      private final String delimiter;

      public DelimiterFilter(String prefix, String delimiter) {
         this.prefix = prefix;
         this.delimiter = delimiter;
      }

      public boolean apply(StorageMetadata metadata) {
         if (prefix == null)
            return metadata.getName().indexOf(delimiter) == -1;
         // ensure we don't accidentally append twice
         String toMatch = prefix.endsWith("/") ? prefix : prefix + delimiter;
         if (metadata.getName().startsWith(toMatch)) {
            String unprefixedName = metadata.getName().replaceFirst(toMatch, "");
            if (unprefixedName.equals("")) {
               // we are the prefix in this case, return false
               return false;
            }
            return unprefixedName.indexOf(delimiter) == -1;
         }
         return false;
      }
   }

   protected static class CommonPrefixes implements Function<StorageMetadata, String> {
      private final String prefix;
      private final String delimiter;
      public static final String NO_PREFIX = "NO_PREFIX";

      public CommonPrefixes(String prefix, String delimiter) {
         this.prefix = prefix;
         this.delimiter = delimiter;
      }

      public String apply(StorageMetadata metadata) {
         String working = metadata.getName();
         if (prefix != null) {
            // ensure we don't accidentally append twice
            String toMatch = prefix.endsWith("/") ? prefix : prefix + delimiter;
            if (working.startsWith(toMatch)) {
               working = working.replaceFirst(toMatch, "");
            }
         }
         if (working.contains(delimiter)) {
            return working.substring(0, working.indexOf(delimiter));
         }
         return NO_PREFIX;
      }
   }

   public static HttpResponseException returnResponseException(int code) {
      HttpResponse response = null;
      response = new HttpResponse(code, null, null);
      return new HttpResponseException(new HttpCommand() {

         public int getRedirectCount() {
            return 0;
         }

         public int incrementRedirectCount() {
            return 0;
         }

         public boolean isReplayable() {
            return false;
         }

         public Exception getException() {
            return null;
         }

         public int getFailureCount() {
            return 0;
         }

         public int incrementFailureCount() {
            return 0;
         }

         public void setException(Exception exception) {

         }

         @Override
         public HttpRequest getCurrentRequest() {
            return new HttpRequest("GET", URI.create("http://stub"));
         }

         @Override
         public void setCurrentRequest(HttpRequest request) {

         }

      }, response);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<String> putBlob(String containerName, Blob blob) {
      checkNotNull(containerName, "containerName must be set");
      checkNotNull(blob, "blob must be set");
      ConcurrentMap<String, Blob> container = getContainerToBlobs().get(containerName);
      String blobKey = blob.getMetadata().getName();

      logger.debug("Put blob with key [%s] to container [%s]", blobKey, containerName);
      if (container == null) {
         return Futures.immediateFailedFuture(new IllegalStateException("containerName not found: " + containerName));
      }

      blob = createUpdatedCopyOfBlobInContainer(containerName, blob);

      container.put(blob.getMetadata().getName(), blob);

      return immediateFuture(Iterables.getOnlyElement(blob.getAllHeaders().get(HttpHeaders.ETAG)));
   }

   protected Blob createUpdatedCopyOfBlobInContainer(String containerName, Blob in) {
      checkNotNull(in, "blob");
      checkNotNull(in.getPayload(), "blob.payload");
      ByteArrayPayload payload = (in.getPayload() instanceof ByteArrayPayload) ? ByteArrayPayload.class.cast(in
               .getPayload()) : null;
      if (payload == null)
         payload = (in.getPayload() instanceof DelegatingPayload) ? (DelegatingPayload.class.cast(in.getPayload())
                  .getDelegate() instanceof ByteArrayPayload) ? ByteArrayPayload.class.cast(DelegatingPayload.class
                  .cast(in.getPayload()).getDelegate()) : null : null;
      try {
         if (payload == null || !(payload instanceof ByteArrayPayload)) {
            MutableContentMetadata oldMd = in.getPayload().getContentMetadata();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.getPayload().writeTo(out);
            payload = (ByteArrayPayload) Payloads.calculateMD5(Payloads.newPayload(out.toByteArray()));
            HttpUtils.copy(oldMd, payload.getContentMetadata());
         } else {
            if (payload.getContentMetadata().getContentMD5() == null)
               Payloads.calculateMD5(in, crypto.md5());
         }
      } catch (IOException e) {
         Throwables.propagate(e);
      }
      Blob blob = blobFactory.create(copy(in.getMetadata()));
      blob.setPayload(payload);
      blob.getMetadata().setContainer(containerName);
      blob.getMetadata().setUri(
               uriBuilders.get().scheme("mem").host(containerName).path(in.getMetadata().getName()).build());
      blob.getMetadata().setLastModified(new Date());
      String eTag = CryptoStreams.hex(payload.getContentMetadata().getContentMD5());
      blob.getMetadata().setETag(eTag);
      // Set HTTP headers to match metadata
      blob.getAllHeaders().replaceValues(HttpHeaders.LAST_MODIFIED,
               Collections.singleton(dateService.rfc822DateFormat(blob.getMetadata().getLastModified())));
      blob.getAllHeaders().replaceValues(HttpHeaders.ETAG, Collections.singleton(eTag));
      copyPayloadHeadersToBlob(payload, blob);
      blob.getAllHeaders().putAll(Multimaps.forMap(blob.getMetadata().getUserMetadata()));
      return blob;
   }

   private void copyPayloadHeadersToBlob(Payload payload, Blob blob) {
      blob.getAllHeaders().putAll(HttpUtils.getContentHeadersFromMetadata(payload.getContentMetadata()));
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Boolean> blobExists(final String containerName, final String key) {
      if (!storageStrategy.containerExists(containerName))
         return immediateFailedFuture(cnfe(containerName));
      return immediateFuture(storageStrategy.blobExists(containerName, key));
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<Blob> getBlob(final String containerName, final String key, GetOptions options) {
      logger.debug("Retrieving blob with key %s from container %s", key, containerName);
      // If the container doesn't exist, an exception is thrown
      if (!storageStrategy.containerExists(containerName)) {
         logger.debug("Container %s does not exist", containerName);
         return immediateFailedFuture(cnfe(containerName));
      }
      // If the blob doesn't exist, a null object is returned
      Map<String, Blob> realContents = getContainerToBlobs().get(containerName);
      if (!realContents.containsKey(key)) {
         logger.debug("Item %s does not exist in container %s", key, containerName);
         return immediateFuture(null);
      }

      Blob blob = realContents.get(key);

      if (options != null) {
         if (options.getIfMatch() != null) {
            if (!blob.getMetadata().getETag().equals(options.getIfMatch()))
               return immediateFailedFuture(returnResponseException(412));
         }
         if (options.getIfNoneMatch() != null) {
            if (blob.getMetadata().getETag().equals(options.getIfNoneMatch()))
               return immediateFailedFuture(returnResponseException(304));
         }
         if (options.getIfModifiedSince() != null) {
            Date modifiedSince = options.getIfModifiedSince();
            if (blob.getMetadata().getLastModified().before(modifiedSince)) {
               HttpResponse response = new HttpResponse(304, null, null);
               return immediateFailedFuture(new HttpResponseException(String.format("%1$s is before %2$s", blob
                     .getMetadata().getLastModified(), modifiedSince), null, response));
            }

         }
         if (options.getIfUnmodifiedSince() != null) {
            Date unmodifiedSince = options.getIfUnmodifiedSince();
            if (blob.getMetadata().getLastModified().after(unmodifiedSince)) {
               HttpResponse response = new HttpResponse(412, null, null);
               return immediateFailedFuture(new HttpResponseException(String.format("%1$s is after %2$s", blob
                     .getMetadata().getLastModified(), unmodifiedSince), null, response));
            }
         }
         blob = copyBlob(blob);

         if (options.getRanges() != null && options.getRanges().size() > 0) {
            byte[] data;
            try {
               data = toByteArray(blob.getPayload().getInput());
            } catch (IOException e) {
               return immediateFailedFuture(new RuntimeException(e));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String s : options.getRanges()) {
               if (s.startsWith("-")) {
                  int length = Integer.parseInt(s.substring(1));
                  out.write(data, data.length - length, length);
               } else if (s.endsWith("-")) {
                  int offset = Integer.parseInt(s.substring(0, s.length() - 1));
                  out.write(data, offset, data.length - offset);
               } else if (s.contains("-")) {
                  String[] firstLast = s.split("\\-");
                  int offset = Integer.parseInt(firstLast[0]);
                  int last = Integer.parseInt(firstLast[1]);
                  int length = (last < data.length) ? last + 1 : data.length - offset;
                  out.write(data, offset, length);
               } else {
                  return immediateFailedFuture(new IllegalArgumentException("first and last were null!"));
               }

            }
            ContentMetadata cmd = blob.getPayload().getContentMetadata();
            blob.setPayload(out.toByteArray());
            HttpUtils.copy(cmd, blob.getPayload().getContentMetadata());
            blob.getPayload().getContentMetadata().setContentLength(new Long(out.toByteArray().length));
         }
      }
      checkNotNull(blob.getPayload(), "payload " + blob);
      return immediateFuture(blob);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ListenableFuture<BlobMetadata> blobMetadata(final String container, final String key) {
      try {
         Blob blob = getBlob(container, key).get();
         return immediateFuture(blob != null ? (BlobMetadata) copy(blob.getMetadata()) : null);
      } catch (Exception e) {
         if (size(filter(getCausalChain(e), KeyNotFoundException.class)) >= 1)
            return immediateFuture(null);
         return immediateFailedFuture(e);
      }
   }

   private Blob copyBlob(Blob blob) {
      Blob returnVal = blobFactory.create(copy(blob.getMetadata()));
      returnVal.setPayload(blob.getPayload());
      copyPayloadHeadersToBlob(blob.getPayload(), returnVal);
      return returnVal;
   }

   public ConcurrentMap<String, ConcurrentMap<String, Blob>> getContainerToBlobs() {
      return containerToBlobs;
   }

   @Override
   protected boolean deleteAndVerifyContainerGone(final String container) {
      storageStrategy.deleteContainer(container);
      return storageStrategy.containerExists(container);
   }

   private ConcurrentMap<String, Location> getContainerToLocation() {
      return containerToLocation;
   }

   @Override
   public ListenableFuture<String> putBlob(String container, Blob blob, PutOptions options) {
      // TODO implement options
      return putBlob(container, blob);
   }

   @Override
   public ListenableFuture<Boolean> createContainerInLocation(Location location, String container,
         CreateContainerOptions options) {
      if (options.isPublicRead())
         throw new UnsupportedOperationException("publicRead");
      return createContainerInLocation(location, container);
   }

   private class TransientStorageStrategy {
      public Iterable<String> getAllContainerNames() {
         return getContainerToBlobs().keySet();
      }

      public boolean containerExists(final String containerName) {
         return getContainerToBlobs().containsKey(containerName);
      }

      public void deleteContainer(final String containerName) {
         getContainerToBlobs().remove(containerName);
      }

      public boolean blobExists(final String containerName, final String blobName) {
         Map<String, Blob> map = containerToBlobs.get(containerName);
         return map != null && map.containsKey(blobName);
      }

      public void removeBlob(final String containerName, final String blobName) {
         if (storageStrategy.containerExists(containerName)) {
            getContainerToBlobs().get(containerName).remove(blobName);
         }
      }
   }
}
