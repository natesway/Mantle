package slimeknights.mantle.client.model;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import net.minecraftforge.client.model.geometry.UnbakedGeometryHelper;
import slimeknights.mantle.client.model.util.ColoredBlockModel;
import slimeknights.mantle.client.model.util.DynamicBakedWrapper;
import slimeknights.mantle.client.model.util.ModelConfigurationWrapper;
import slimeknights.mantle.client.model.util.ModelHelper;
import slimeknights.mantle.client.model.util.ModelTextureIteratable;
import slimeknights.mantle.client.model.util.SimpleBlockModel;
import slimeknights.mantle.item.RetexturedBlockItem;
import slimeknights.mantle.util.RetexturedHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Model that dynamically retextures a list of textures based on data from {@link RetexturedHelper}.
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class RetexturedModel implements IUnbakedGeometry<RetexturedModel> {
  private final ColoredBlockModel model;
  private final Set<String> retextured;


  @Override
  public BakedModel bake(IGeometryBakingContext context, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides, ResourceLocation modelLocation) {

    BakedModel baked = model.bake(context, bakery, spriteGetter, modelState, overrides, modelLocation);
    return new Baked(baked, context, model, modelState, getAllRetextured(context, model.getModel(), retextured));
  }

  @Override
  public Collection<Material> getMaterials(IGeometryBakingContext context, Function<ResourceLocation, UnbakedModel> modelGetter, Set<Pair<String, String>> missingTextureErrors) {
    return model.getMaterials(context, modelGetter, missingTextureErrors);
  }

  /**
   * Gets a list of all names to retexture based on the block model texture references
   * @param context        Model config instance
   * @param model        Model fallback
   * @param originalSet  Original list of names to retexture
   * @return  Set of textures including parent textures
   */
  public static Set<String> getAllRetextured(IGeometryBakingContext context, SimpleBlockModel model,
                                             Set<String> originalSet) {
    Set<String> retextured = Sets.newHashSet(originalSet);
    for (Map<String,Either<Material, String>> textures : ModelTextureIteratable.of(context, model)) {
      textures.forEach((name, either) ->
        either.ifRight(parent -> {
          if (retextured.contains(parent)) {
            retextured.add(name);
          }
        })
      );
    }
    return ImmutableSet.copyOf(retextured);
  }


  /** Registered model loader instance registered */
  public static class Loader implements IGeometryLoader<RetexturedModel> {
    public static final Loader INSTANCE = new Loader();
    private Loader() {}

    @Override
    public RetexturedModel read(JsonObject json,JsonDeserializationContext context) {
      // get base model
      ColoredBlockModel model = ColoredBlockModel.deserialize(context, json);

      // get list of textures to retexture
      Set<String> retextured = getRetextured(json);

      // return retextured model
      return new RetexturedModel(model, retextured);
    }

    /**
     * Gets the list of retextured textures from the model
     * @param json  Model json
     * @return  List of textures
     */
    public static Set<String> getRetextured(JsonObject json) {
      if (json.has("retextured")) {
        // if an array, set from each texture in array
        JsonElement retextured = json.get("retextured");
        if (retextured.isJsonArray()) {
          JsonArray array = retextured.getAsJsonArray();
          if (array.size() == 0) {
            throw new JsonSyntaxException("Must have at least one texture in retextured");
          }
          ImmutableSet.Builder<String> builder = ImmutableSet.builder();
          for (int i = 0; i < array.size(); i++) {
            builder.add(GsonHelper.convertToString(array.get(i), "retextured[" + i + "]"));
          }
          return builder.build();
        }
        // if string, single texture
        if (retextured.isJsonPrimitive()) {
          return ImmutableSet.of(retextured.getAsString());
        }
      }
      // if neither or missing, error
      throw new JsonSyntaxException("Missing retextured, expected to find a String or a JsonArray");
    }
  }

  /** Baked variant of the model, used to swap out quads based on the texture */
  public static class Baked extends DynamicBakedWrapper<BakedModel> {
    /** Cache of texture name to baked model */
    private final Map<ResourceLocation,BakedModel> cache = new ConcurrentHashMap<>();
    /* Properties for rebaking */
    private final IGeometryBakingContext owner;
    private final ColoredBlockModel model;
    private final ModelState transform;
    /** List of texture names that are retextured */
    private final Set<String> retextured;

    public Baked(BakedModel baked, IGeometryBakingContext owner, ColoredBlockModel model, ModelState transform, Set<String> retextured) {
      super(baked);
      this.model = model;
      this.owner = owner;
      this.transform = transform;
      this.retextured = retextured;
    }


    /**
     * Gets the model with the given texture applied
     * @param name  Texture location
     * @return  Retextured model
     */
    private BakedModel getRetexturedModel(ResourceLocation name) {
      return model.bakeDynamic(new RetexturedConfiguration(owner, retextured, name), transform);
    }

    /**
     * Gets a cached retextured model, computing it if missing from the cache
     * @param block  Block determining the texture
     * @return  Retextured model
     */
    private BakedModel getCachedModel(Block block) {
      return cache.computeIfAbsent(ModelHelper.getParticleTexture(block), this::getRetexturedModel);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
      // if particle is retextured, fetch particle from the cached model
      if (retextured.contains("particle")) {
        Block block = data.get(RetexturedHelper.BLOCK_PROPERTY);
        if (block != null) {
          return getCachedModel(block).getParticleIcon(data);
        }
      }
      return originalModel.getParticleIcon(data);
    }

    @Nonnull
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random,
                                    ModelData data, RenderType renderType) {
      Block block = data.get(RetexturedHelper.BLOCK_PROPERTY);
      if (block == null) {
        return originalModel.getQuads(state, direction, random);
      }
      return getCachedModel(block).getQuads(state, direction, random,data,renderType);
    }

    @Override
    public ItemOverrides getOverrides() {
      return RetexturedOverride.INSTANCE;
    }
  }

  /**
   * Model configuration wrapper to retexture the block
   */
  public static class RetexturedConfiguration extends ModelConfigurationWrapper {
    /** List of textures to retexture */
    private final Set<String> retextured;
    /** Replacement texture */
    private final Material texture;

    /**
     * Creates a new configuration wrapper
     * @param base        Original model configuration
     * @param retextured  Set of textures that should be retextured
     * @param texture     New texture to replace those in the set
     */
    public RetexturedConfiguration(IGeometryBakingContext base, Set<String> retextured, ResourceLocation texture) {
      super(base);
      this.retextured = retextured;
      this.texture = new Material(TextureAtlas.LOCATION_BLOCKS, texture);


    }

    @Override
    public boolean hasMaterial(String name) {
      if (retextured.contains(name)) {
        return !MissingTextureAtlasSprite.getLocation().equals(texture.texture());
      }
      return super.hasMaterial(name);
    }

    @Override
    public Material getMaterial(String name) {
      if (retextured.contains(name)) {
        return texture;
      }
      return super.getMaterial(name);
    }
  }

  /** Override list to swap the texture in from NBT */
  private static class RetexturedOverride extends ItemOverrides {
    private static final RetexturedOverride INSTANCE = new RetexturedOverride();

    @Nullable
    @Override
    public BakedModel resolve(BakedModel originalModel, ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int pSeed) {
      if (stack.isEmpty() || !stack.hasTag()) {
        return originalModel;
      }

      // get the block first, ensuring its valid
      Block block = RetexturedBlockItem.getTexture(stack);
      if (block == Blocks.AIR) {
        return originalModel;
      }

      // if valid, use the block
      return ((Baked)originalModel).getCachedModel(block);
    }
  }
}
