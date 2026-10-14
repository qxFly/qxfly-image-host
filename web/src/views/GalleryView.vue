<template>
  <div class="page gallery" :class="{ 'has-selection': selectedIds.size > 0 }">
    <!-- 手机端：目录树收进抽屉，内容区独占屏幕 -->
    <div v-if="isMobile" class="toolbar gallery-mobile-bar">
      <el-button size="small" @click="treeVisible = true">
        <el-icon><Files /></el-icon>&nbsp;目录
      </el-button>
      <span class="text-muted">当前：{{ query.directory || '全部' }}</span>
    </div>
    <el-drawer v-model="treeVisible" title="目录" direction="ltr" size="78%" class="gallery-tree-drawer">
      <el-tree
        v-if="treeData.length"
        :key="treeVersion"
        :data="treeData"
        node-key="path"
        :props="{ label: 'label', children: 'children' }"
        :current-node-key="query.directory"
        :default-expanded-keys="expandedKeys"
        highlight-current
        :expand-on-click-node="false"
        @node-click="onNodeClick">
        <template #default="{ data }">
          <span class="tree-node">
            <span class="tree-node__name">{{ data.label }}</span>
            <span class="tree-node__count">{{ data.totalCount }}</span>
          </span>
        </template>
      </el-tree>
      <div class="side-all" :class="{ 'side-all--active': !query.directory }" @click="selectDirectory(undefined)">
        全部（{{ dirTree.total }}）
      </div>
    </el-drawer>

    <div class="gallery-body">
      <el-card v-if="!isMobile" class="gallery-side" shadow="never">
        <div class="side-head">
          <span class="side-title">目录</span>
          <el-button link size="small" :loading="treeLoading" @click="loadTree">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
        <div
          class="side-all"
          :class="{ 'side-all--active': !query.directory }"
          @click="selectDirectory(undefined)">
          全部（{{ dirTree.total }}）
        </div>
        <el-tree
          v-if="treeData.length"
          :key="treeVersion"
          ref="treeRef"
          :data="treeData"
          node-key="path"
          :props="{ label: 'label', children: 'children' }"
          :current-node-key="query.directory"
          :default-expanded-keys="expandedKeys"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onNodeClick">
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-node__name">{{ data.label }}</span>
              <span class="tree-node__count">{{ data.totalCount }}</span>
            </span>
          </template>
        </el-tree>
        <el-empty
          v-else-if="!treeLoading"
          description="没有目录"
          :image-size="50" />
      </el-card>

      <div class="gallery-main">
        <div class="gallery-panel">
          <div class="panel-row">
            <el-select
              v-model="query.matchMode"
              style="width: 100px"
              @change="load(1)">
              <el-option label="模糊" value="fuzzy" />
              <el-option label="精准" value="exact" />
              <el-option label="目录" value="prefix" />
            </el-select>
            <el-input
              v-model="query.keyword"
              :placeholder="searchPlaceholder"
              clearable
              style="width: 250px"
              @keyup.enter="load(1)"
              @clear="load(1)">
              <template #prefix
                ><el-icon><Search /></el-icon
              ></template>
            </el-input>
            <el-select
              v-if="canManage"
              v-model="query.storageConfigId"
              placeholder="全部存储后端"
              clearable
              style="width: 180px"
              @change="onStorageChange">
              <el-option
                v-for="item in configs"
                :key="item.id"
                :label="item.name"
                :value="item.id" />
            </el-select>
            <el-button type="primary" @click="load(1)">查询</el-button>
            <div class="toolbar-spacer"></div>
            <el-button @click="load()"
              ><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button
            >
            <el-button v-if="canManage" @click="importVisible = true">
              <el-icon><Download /></el-icon>&nbsp;从存储导入
            </el-button>
          </div>

          <div class="panel-row panel-row--divided">
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="partiallySelected"
              :disabled="images.length === 0"
              @change="toggleSelectAll">
              全选本页
            </el-checkbox>
            <span class="selection-count">
              已选 <b>{{ selectedIds.size }}</b> 张
              <span class="text-muted">（本页 {{ images.length }} 张）</span>
            </span>
            <el-tooltip
              placement="top"
              content="点击卡片即可选择；Shift + 点击连选；Ctrl / ⌘ + A 全选本页；Esc 取消选择">
              <el-icon class="hint-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
            <div class="toolbar-spacer"></div>
            <el-dropdown
              :disabled="images.length === 0"
              @command="downloadBatch">
              <el-button
                type="primary"
                plain
                :loading="downloading"
                :disabled="images.length === 0">
                <el-icon><Download /></el-icon>&nbsp;批量下载
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    command="selected"
                    :disabled="selectedIds.size === 0">
                    下载选中（{{ selectedIds.size }} 张）
                  </el-dropdown-item>
                  <el-dropdown-item command="page"
                    >下载本页（{{ images.length }} 张）</el-dropdown-item
                  >
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-if="canManage"
              type="warning"
              plain
              @click="migrateVisible = true">
              <el-icon><Sort /></el-icon>&nbsp;批量迁移
            </el-button>
            <el-button v-if="canManage" @click="tasksVisible = true">
              <el-icon><List /></el-icon>&nbsp;迁移任务
            </el-button>
            <el-button
              v-if="canManage"
              type="danger"
              plain
              :disabled="selectedIds.size === 0"
              :loading="deleting"
              @click="removeSelected">
              <el-icon><Delete /></el-icon>&nbsp;删除选中
            </el-button>
          </div>
        </div>

        <div class="crumb">
          <span class="text-muted">当前位置：</span>
          <span class="mono">{{
            query.directory === ROOT_DIRECTORY
              ? "（根目录）"
              : query.directory || "全部"
          }}</span>
          <span v-if="query.keyword" class="text-muted"
            >　关键字：{{ query.keyword }}</span
          >
          <el-button
            v-if="query.directory"
            link
            type="primary"
            size="small"
            @click="selectDirectory(undefined)">
            返回全部
          </el-button>
        </div>

        <el-empty
          v-if="!loading && images.length === 0"
          :description="emptyHint" />

        <div v-else v-loading="loading" class="image-grid">
          <ImageCard
            v-for="image in images"
            :key="image.id"
            :image="image"
            :selected="selectedIds.has(image.id)"
            :can-manage="canManage"
            @toggle="toggleSelect"
            @remove="remove"
            @links="openLinks" />
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="query.page"
            :page-size="query.size"
            :total="total"
            :pager-count="7"
            layout="total, prev, pager, next, jumper"
            background
            @current-change="load()" />
          <div class="pager__size">
            <span class="text-muted">每页</span>
            <el-select
              v-model="sizeOption"
              style="width: 108px"
              @change="onSizeOptionChange">
              <el-option
                v-for="item in SIZE_OPTIONS"
                :key="item"
                :label="item + ' 条'"
                :value="item" />
              <el-option label="自定义" :value="CUSTOM_SIZE" />
            </el-select>
            <el-input-number
              v-if="sizeOption === CUSTOM_SIZE"
              v-model="customSize"
              :min="1"
              :max="MAX_PAGE_SIZE"
              :step="10"
              size="small"
              style="width: 120px"
              @change="onCustomSizeChange" />
            <span v-if="sizeOption === CUSTOM_SIZE" class="text-muted"
              >1 - {{ MAX_PAGE_SIZE }}</span
            >
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="shareVisible" title="分享选中的图片" width="520px">
      <el-form label-width="96px">
        <el-form-item label="相册标题">
          <el-input v-model="shareForm.title" placeholder="不填就是「未命名相册」" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="访问密码">
          <el-input v-model="shareForm.password" placeholder="留空表示任何人拿到链接都能看" show-password />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="shareForm.expiresInDays" style="width: 180px">
            <el-option label="长期有效" :value="0" />
            <el-option label="1 天" :value="1" />
            <el-option label="7 天" :value="7" />
            <el-option label="30 天" :value="30" />
          </el-select>
        </el-form-item>
        <el-form-item label="图片">
          <span class="text-muted">已选 {{ selectedIds.size }} 张；创建后可随时在「分享链接」里撤销</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shareVisible = false">取消</el-button>
        <el-button type="primary" :loading="sharing" @click="createShareLink">创建并复制链接</el-button>
      </template>
    </el-dialog>

    <Transition name="el-fade-in-linear">
      <div v-if="selectedIds.size > 0" class="selection-float">
        <span class="selection-float__count"
          >已选 <b>{{ selectedIds.size }}</b> 张</span
        >
        <el-button size="small" @click="clearSelection">取消选择</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="downloading"
          @click="downloadBatch('selected')">
          <el-icon><Download /></el-icon>&nbsp;下载选中
        </el-button>
        <el-button
          v-if="canManage"
          size="small"
          type="success"
          plain
          @click="openShare">
          <el-icon><Share /></el-icon>&nbsp;分享
        </el-button>
        <el-button
          v-if="canManage"
          size="small"
          type="warning"
          plain
          @click="migrateVisible = true">
          <el-icon><Sort /></el-icon>&nbsp;批量迁移
        </el-button>
        <el-button
          v-if="canManage"
          size="small"
          type="danger"
          plain
          :loading="deleting"
          @click="removeSelected">
          <el-icon><Delete /></el-icon>&nbsp;删除选中
        </el-button>
      </div>
    </Transition>

    <ImportDialog v-model="importVisible" @imported="onDataChanged" />
    <LinkPickerDialog
      v-model="linksVisible"
      :image="linksImage"
      :mode="linksMode" />
    <MigrationTasksDialog v-model="tasksVisible" />
    <MigrateDialog
      v-model="migrateVisible"
      :image-ids="Array.from(selectedIds)"
      :filter-storage-config-id="query.storageConfigId"
      :filter-keyword="query.keyword"
      :filter-total="total"
      @finished="onDataChanged" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import ImageCard from "@/components/ImageCard.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import LinkPickerDialog from "@/components/LinkPickerDialog.vue";
import MigrateDialog from "@/components/MigrateDialog.vue";
import MigrationTasksDialog from "@/components/MigrationTasksDialog.vue";
import {
  deleteImage,
  deleteImages,
  downloadImagesZip,
  fetchDirectories,
  fetchImages,
} from "@/api/images";
import { fetchStorageConfigs } from "@/api/storage";
import { createShare } from "@/api/shares";
import { useAuthStore } from "@/stores/auth";
import { saveBlob, zipFileName } from "@/utils/download";
import { copyText } from "@/utils/format";
import { useIsMobile } from "@/utils/responsive";
import type {
  DirectoryNodeVO,
  DirectoryTreeVO,
  ImageVO,
  LinkMode,
  StorageConfigVO,
} from "@/types";

/** 与后端 ImageService.ROOT_DIRECTORY 保持一致 */
const ROOT_DIRECTORY = "__root__";

const SIZE_OPTIONS = [30, 50, 100, 200];
const CUSTOM_SIZE = -1;
const MAX_PAGE_SIZE = 500;
const SIZE_STORAGE_KEY = "qxfly_image_host_page_size";

const auth = useAuthStore();
/** 普通用户只能浏览 / 查看 / 下载：导入、迁移、删除等入口都不显示 */
const canManage = computed(() => auth.isSuperAdmin);
const loading = ref(false);
const images = ref<ImageVO[]>([]);
const configs = ref<StorageConfigVO[]>([]);
const total = ref(0);

const importVisible = ref(false);
const migrateVisible = ref(false);
const tasksVisible = ref(false);
const selectedIds = ref<Set<number>>(new Set());
/** 上一次点选的卡片位置，Shift 连选要从这里算区间 */
const lastClickedIndex = ref(-1);
/** 批量下载进行中（打包由后端完成，这里只等响应） */
const downloading = ref(false);
/** 手机端目录抽屉 */
const isMobile = useIsMobile();
const treeVisible = ref(false);
/** 批量删除进行中（逐张搬进回收站目录，后端处理） */
const deleting = ref(false);
/** 分享：把选中的图片打包成公开相册 */
const shareVisible = ref(false);
const sharing = ref(false);
const shareForm = reactive({ title: "", password: "", expiresInDays: 0 });

/** 链接选择框（下载 / 直链 / MD 都先选线路） */
const linksVisible = ref(false);
const linksImage = ref<ImageVO | null>(null);
const linksMode = ref<LinkMode>("copy");

const treeRef = ref();
const treeLoading = ref(false);
const treeVersion = ref(0);
const dirTree = ref<DirectoryTreeVO>({ total: 0, rootFileCount: 0, nodes: [] });

interface TreeNode {
  path: string;
  label: string;
  totalCount: number;
  children: TreeNode[];
}

function readStoredSize(): number {
  const stored = Number(localStorage.getItem(SIZE_STORAGE_KEY));
  if (!Number.isFinite(stored) || stored < 1) {
    return SIZE_OPTIONS[0];
  }
  return Math.min(Math.floor(stored), MAX_PAGE_SIZE);
}

const initialSize = readStoredSize();
const sizeOption = ref<number>(
  SIZE_OPTIONS.includes(initialSize) ? initialSize : CUSTOM_SIZE,
);
const customSize = ref<number>(initialSize);

const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  storageConfigId?: number;
  directory?: string;
  matchMode: string;
}>({
  page: 1,
  size: initialSize,
  keyword: "",
  storageConfigId: undefined,
  directory: undefined,
  matchMode: "fuzzy",
});

const searchPlaceholder = computed(() => {
  if (query.matchMode === "exact") {
    return "完整对象键，或完整文件名";
  }
  if (query.matchMode === "prefix") {
    return "目录路径，如 api/images/2026/10/12/002";
  }
  return "文件名 / 对象键包含";
});

/** 搜不到时给出下一步提示，避免在"精准"模式下拿目录去搜却不知道为什么没结果 */
const emptyHint = computed(() => {
  if (!query.keyword) {
    return "暂无图片";
  }
  if (query.matchMode === "exact") {
    return "精准模式要求文件名或对象键完全相等。如果你填的是一段目录，请换成「目录」模式";
  }
  if (query.matchMode === "prefix") {
    return "没有对象键或文件名以「" + query.keyword + "」开头";
  }
  return "没有包含「" + query.keyword + "」的图片";
});

/** 目录树 -> el-tree 数据；根目录下的散图单独一个节点 */
const treeData = computed<TreeNode[]>(() => {
  const convert = (nodes: DirectoryNodeVO[]): TreeNode[] =>
    nodes.map((node) => ({
      path: node.path,
      label: node.name,
      totalCount: node.totalCount,
      children: convert(node.children),
    }));
  const result: TreeNode[] = [];
  if (dirTree.value.rootFileCount > 0) {
    result.push({
      path: ROOT_DIRECTORY,
      label: "（根目录）",
      totalCount: dirTree.value.rootFileCount,
      children: [],
    });
  }
  return result.concat(convert(dirTree.value.nodes));
});

/** 默认展开前两级，避免超大树一次性全展开 */
const expandedKeys = computed(() => {
  const keys: string[] = [];
  const walk = (nodes: TreeNode[], depth: number) => {
    nodes.forEach((node) => {
      if (depth < 2) {
        keys.push(node.path);
        walk(node.children, depth + 1);
      }
    });
  };
  walk(treeData.value, 0);
  return keys;
});

/** 本页被选中的数量：选中集只可能来自本页（翻页/筛选会清空） */
const pageSelectedCount = computed(() =>
  images.value.reduce(
    (count, item) => count + (selectedIds.value.has(item.id) ? 1 : 0),
    0,
  ),
);
const allSelected = computed(
  () =>
    images.value.length > 0 && pageSelectedCount.value === images.value.length,
);
const partiallySelected = computed(
  () => pageSelectedCount.value > 0 && !allSelected.value,
);

/**
 * 选择 / 取消选择一张卡片。
 * Shift 点击时按「最近一次点选的位置 -> 当前位置」整段设置为同样的状态（和文件管理器一致）。
 */
function toggleSelect(id: number, shift = false) {
  const index = images.value.findIndex((item) => item.id === id);
  const next = new Set(selectedIds.value);
  const target = !next.has(id);

  if (shift && lastClickedIndex.value >= 0 && index >= 0) {
    const from = Math.min(lastClickedIndex.value, index);
    const to = Math.max(lastClickedIndex.value, index);
    for (let i = from; i <= to; i++) {
      if (target) {
        next.add(images.value[i].id);
      } else {
        next.delete(images.value[i].id);
      }
    }
  } else if (target) {
    next.add(id);
  } else {
    next.delete(id);
  }

  selectedIds.value = next;
  lastClickedIndex.value = index;
}

function selectAllOnPage() {
  selectedIds.value = new Set(images.value.map((item) => item.id));
  lastClickedIndex.value = -1;
}

function toggleSelectAll() {
  if (allSelected.value) {
    clearSelection();
  } else {
    selectAllOnPage();
  }
}

function clearSelection() {
  selectedIds.value = new Set();
  lastClickedIndex.value = -1;
}

/** Esc 取消选择；Ctrl / ⌘ + A 全选本页（输入框里不抢快捷键） */
function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null;
  const typing =
    !!target &&
    (target.tagName === "INPUT" ||
      target.tagName === "TEXTAREA" ||
      target.isContentEditable);
  if (event.key === "Escape" && selectedIds.value.size > 0) {
    clearSelection();
    return;
  }
  if (
    (event.ctrlKey || event.metaKey) &&
    event.key.toLowerCase() === "a" &&
    !typing &&
    images.value.length > 0
  ) {
    event.preventDefault();
    selectAllOnPage();
  }
}

function applySize(value: number) {
  const safe = Math.min(Math.max(Math.floor(value), 1), MAX_PAGE_SIZE);
  query.size = safe;
  localStorage.setItem(SIZE_STORAGE_KEY, String(safe));
  load(1);
}

function onSizeOptionChange(value: number) {
  if (value === CUSTOM_SIZE) {
    customSize.value = query.size;
    return;
  }
  applySize(value);
}

function onCustomSizeChange(value: number | undefined) {
  if (!value) {
    return;
  }
  applySize(value);
}

async function loadTree() {
  treeLoading.value = true;
  try {
    dirTree.value = (await fetchDirectories(query.storageConfigId)).data;
    treeVersion.value += 1;
  } catch {
    // 拦截器已提示
  } finally {
    treeLoading.value = false;
  }
}

function onStorageChange() {
  selectDirectory(undefined);
  loadTree();
  load(1);
}

function onNodeClick(node: TreeNode) {
  selectDirectory(node.path);
}

function selectDirectory(path?: string) {
  query.directory = path;
  treeVisible.value = false;
  load(1);
}

function onDataChanged() {
  loadTree();
  load(1);
}

async function load(page?: number, retried = false) {
  if (typeof page === "number") {
    query.page = page;
  }
  loading.value = true;
  try {
    const params: {
      page: number;
      size: number;
      keyword?: string;
      storageConfigId?: number;
      directory?: string;
      matchMode?: string;
    } = {
      page: query.page,
      size: query.size,
    };
    if (query.keyword) {
      params.keyword = query.keyword;
      params.matchMode = query.matchMode;
    }
    if (query.storageConfigId) {
      params.storageConfigId = query.storageConfigId;
    }
    if (query.directory) {
      params.directory = query.directory;
    }
    const result = await fetchImages(params);
    images.value = result.data.records;
    total.value = result.data.total;
    clearSelection();
    // 删掉当页最后一张后当前页可能已经越界，自动退到最后一页再取一次
    const pages = Math.max(1, Math.ceil(total.value / query.size));
    if (!retried && query.page > pages) {
      query.page = pages;
      await load(undefined, true);
    }
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false;
  }
}

/** 打开「选择线路」弹窗：下载 / 复制直链 / 复制 Markdown 都先让用户挑 CDN */
function openLinks(image: ImageVO, mode: LinkMode) {
  linksImage.value = image;
  linksMode.value = mode;
  linksVisible.value = true;
}

/**
 * 批量下载：选中的几张，或当前页全部，由后端打包成一个 zip。
 * 图片多的时候在服务端边取边打包，不会把整包先读进内存。
 */
async function downloadBatch(scope: string) {
  const ids =
    scope === "selected"
      ? Array.from(selectedIds.value)
      : images.value.map((item) => item.id);
  if (ids.length === 0) {
    ElMessage.warning(
      scope === "selected" ? "请先勾选要下载的图片" : "本页没有可下载的图片",
    );
    return;
  }
  downloading.value = true;
  try {
    const blob = await downloadImagesZip(ids);
    saveBlob(blob, zipFileName());
    ElMessage.success("已开始下载 " + ids.length + " 张图片");
  } catch {
    // 拦截器已提示
  } finally {
    downloading.value = false;
  }
}

/** 打开发分享对话框 */
function openShare() {
  shareForm.title = "";
  shareForm.password = "";
  shareForm.expiresInDays = 0;
  shareVisible.value = true;
}

/** 创建分享并直接把链接复制到剪贴板 */
async function createShareLink() {
  const ids = Array.from(selectedIds.value);
  if (ids.length === 0) {
    ElMessage.warning("请先勾选要分享的图片");
    return;
  }
  sharing.value = true;
  try {
    const created = await createShare({
      title: shareForm.title || undefined,
      imageIds: ids,
      password: shareForm.password || undefined,
      expiresInDays: shareForm.expiresInDays || undefined,
    });
    const url = window.location.origin + window.location.pathname + created.data.url;
    const ok = await copyText(url);
    shareVisible.value = false;
    ElMessage.success(
      ok
        ? "分享已创建，链接已复制：" + url
        : "分享已创建：" + url,
    );
  } catch {
    // 拦截器已提示
  } finally {
    sharing.value = false;
  }
}

async function remove(image: ImageVO) {
  try {
    await deleteImage(image.id);
    ElMessage.success("已移入回收站，可在左侧「回收站」里恢复或彻底删除");
    await load();
    await loadTree();
  } catch {
    // 拦截器已提示
  }
}

/**
 * 批量删除：把选中的图片一次性移入回收站。
 * 与单张删除同一套语义（可恢复），所以确认框的措辞是「移入回收站」而不是「彻底删除」。
 */
async function removeSelected() {
  const ids = Array.from(selectedIds.value);
  if (ids.length === 0) {
    ElMessage.warning("请先勾选要删除的图片");
    return;
  }
  try {
    await ElMessageBox.confirm(
      "将把这 " +
        ids.length +
        " 张图片移入回收站，可在左侧「回收站」里恢复或彻底删除，确认删除？",
      "批量删除",
      {
        type: "warning",
        confirmButtonText: "移入回收站",
        confirmButtonClass: "el-button--danger",
      },
    );
  } catch {
    return; // 取消
  }
  deleting.value = true;
  try {
    const result = await deleteImages(ids);
    ElMessage.success(
      "已把 " +
        result.data +
        " 张图片移入回收站，可在左侧「回收站」里恢复或彻底删除",
    );
    await load();
    await loadTree();
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false;
  }
}

onMounted(async () => {
  window.addEventListener("keydown", onKeydown);
  await load(1);
  await loadTree();
  try {
    configs.value = (await fetchStorageConfigs()).data;
  } catch {
    // 忽略
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKeydown);
});
</script>

<style scoped>
.gallery-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.gallery-mobile-bar {
  margin-bottom: 10px;
}

/* 手机端目录树在抽屉里，内容区自然占满整宽 */
.gallery-body > .gallery-content {
  flex: 1;
  min-width: 0;
}

@media (max-width: 820px) {
  .gallery-side {
    display: none;
  }

  .gallery-body {
    gap: 0;
  }
}

/* 有选中项时底部会浮出一条操作栏，给页面留出空间避免挡住分页 */
.gallery.has-selection {
  padding-bottom: 96px;
}

.gallery-side {
  flex: 0 0 250px;
  width: 250px;
  max-height: calc(100vh - 130px);
  overflow: auto;
  position: sticky;
  top: 0;
  border-radius: 12px;
  border-color: #eef0f4;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.side-title {
  font-size: 13px;
  font-weight: 600;
}

.side-all {
  padding: 4px 8px;
  margin-bottom: 4px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.18s;
}

.side-all:hover {
  background: #f5f7fa;
}

.side-all--active {
  color: #409eff;
  background: #ecf5ff;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 6px;
  font-size: 13px;
}

.tree-node__count {
  color: #a8abb2;
  font-size: 12px;
}

.gallery-main {
  flex: 1;
  min-width: 0;
}

/* 筛选 + 批量操作合成一块面板，比原来一长串控件清爽 */
.gallery-panel {
  padding: 12px 14px;
  margin-bottom: 14px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 12px;
}

.panel-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.panel-row--divided {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

.selection-count {
  font-size: 13px;
  color: #606266;
}

.selection-count b {
  margin: 0 2px;
  color: var(--el-color-primary);
}

.hint-icon {
  color: #c0c4cc;
  cursor: help;
}

.crumb {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
}

.image-grid {
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 18px;
}

/* 整条分页栏居中：每页选择 + 总数 + 翻页 + 跳页在同一行的中间 */
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  flex-wrap: wrap;
}

.pager__size {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 选中后用浮动条承接批量操作：滚到哪都能直接下载 / 迁移 */
.selection-float {
  position: fixed;
  left: 50%;
  bottom: 26px;
  z-index: 2000;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 999px;
  box-shadow: 0 12px 32px rgba(31, 42, 68, 0.18);
}

.selection-float__count {
  font-size: 13px;
  color: #606266;
}

.selection-float__count b {
  margin: 0 2px;
  color: var(--el-color-primary);
}
</style>
