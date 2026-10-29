---
name: 阿里巴巴前端规约（F2E-Spec）完整开发规范
version: 1.0.0
description: 阿里巴巴经济体内部广泛使用的一套前端编码和工程规范，致力于通过统一编码风格、普及最佳实践和代码缺陷检查，帮助团队降低协作成本、提升前端项目的可维护性和稳定性。包含编码规约和工程规约两大类，配套ESLint、stylelint、commitlint等Linter工具。
---

# 阿里巴巴前端规约（F2E-Spec）完整开发规范

## 前言

「阿里巴巴前端规约」（F2E-Spec）是阿里巴巴经济体内部广泛使用的一套前端编码和工程规范，致力于通过统一编码风格、普及最佳实践和代码缺陷检查，帮助团队降低协作成本、提升前端项目的可维护性和稳定性。

本规范主要包括「规约文档」和「配套工具」两部分。规约文档分为编码规约和工程规约两大类，配套工具则包括 ESLint、stylelint、commitlint 等 Linter 的定制规则包，以及一键接入工具 F2ELint。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、JavaScript 编码规范

JavaScript 编码规约主要包含编码风格、语言特性、注释、命名、配套工具等几个部分，面向的 ECMAScript 语言版本是 ES6+。

### 1.1 缩进

**【强制】使用 2 个空格缩进，不要使用 4 个空格或 tab 缩进。** ESLint: `indent`

**反例：**
```javascript
// ❌ 错误：使用 4 个空格缩进
function foo() {
    let name;
}
```

**正例：**
```javascript
// ✅ 正确：使用 2 个空格缩进
function foo() {
  let name;
}
```

### 1.2 分号

**【强制】语句必须以分号结束。** ESLint: `semi`

统一以分号结束语句，可以避免 JS 引擎自动分号插入机制（ASI）的怪异行为，在语义上也更加明确。

**反例：**
```javascript
// ❌ 错误：未加分号，可能导致 Uncaught ReferenceError
const luke = {}
const leia = {}
[luke, leia].forEach((jedi) => { jedi.father = 'vader' })

// ❌ 错误：可能导致函数返回 undefined 而非预期值
function foo() {
  return
    'Result want to be returned'
}
```

**正例：**
```javascript
// ✅ 正确：以分号结束语句
const luke = {};
const leia = {};
[luke, leia].forEach((jedi) => {
  jedi.father = 'vader';
});

function foo() {
  return 'Result want to be returned';
}
```

### 1.3 逗号

**【强制】对于逗号分隔的多行结构，不使用行首逗号。** ESLint: `comma-style`

**【强制】对于逗号分隔的多行结构，始终加上最后一个逗号（trailing comma）。** ESLint: `comma-dangle`

这样可以使增删行更加容易，也会使 git diffs 更清晰。

**反例：**
```javascript
// ❌ 错误：行首逗号
const story = [
  once
  , upon
  , aTime
];

// ❌ 错误：没有 trailing comma
const hero = {
  firstName: 'Ada',
  lastName: 'Lovelace'
};
```

**正例：**
```javascript
// ✅ 正确：行尾逗号 + trailing comma
const story = [
  once,
  upon,
  aTime,
];

const hero = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  superPower: 'computers',
};
```

### 1.4 引号

**【强制】字符串使用单引号。** ESLint: `quotes`

**反例：**
```javascript
// ❌ 错误：使用双引号
const name = "John";
```

**正例：**
```javascript
// ✅ 正确：使用单引号
const name = 'John';
const template = `Hello, ${name}`; // 模板字符串使用反引号
```

### 1.5 变量声明

**【强制】使用 `const` 声明不会重新赋值的变量，使用 `let` 声明需要重新赋值的变量，禁止使用 `var`。** ESLint: `no-var`, `prefer-const`

**反例：**
```javascript
// ❌ 错误：使用 var
var count = 10;
var name = 'Alice';

// ❌ 错误：应使用 const 却用了 let
let MAX_SIZE = 100;
```

**正例：**
```javascript
// ✅ 正确：使用 const 和 let
const MAX_SIZE = 100;
let count = 10;
const user = { name: 'Alice' };
```

### 1.6 相等比较

**【强制】使用 `===` 和 `!==` 代替 `==` 和 `!=`。** ESLint: `eqeqeq`

**反例：**
```javascript
// ❌ 错误：使用 ==，类型转换可能导致意外结果
if (value == '0') { }
if (count != null) { }
```

**正例：**
```javascript
// ✅ 正确：使用 === 和 !==
if (value === '0') { }
if (count !== null) { }
```

### 1.7 函数规范

**【推荐】使用箭头函数处理匿名函数。** ESLint: `prefer-arrow-callback`

**反例：**
```javascript
// ❌ 错误：使用传统匿名函数
[1, 2, 3].map(function(x) {
  return x * 2;
});
```

**正例：**
```javascript
// ✅ 正确：使用箭头函数
[1, 2, 3].map((x) => x * 2);
```

**【强制】不要在循环中声明函数。** ESLint: `no-loop-func`

### 1.8 命名规范

**【强制】变量和函数使用驼峰命名法（camelCase），类名使用帕斯卡命名法（PascalCase）。**

**反例：**
```javascript
// ❌ 错误
const user_name = 'Alice';
function GetUser() { }
class user { }
```

**正例：**
```javascript
// ✅ 正确
const userName = 'Alice';
function getUser() { }
class User { }
```

**【强制】常量使用全大写下划线分割（UPPER_CASE）。**

**反例：**
```javascript
// ❌ 错误
const maxCount = 100;
```

**正例：**
```javascript
// ✅ 正确
const MAX_COUNT = 100;
const API_BASE_URL = 'https://api.example.com';
```


## 二、TypeScript 编码规范

TypeScript 是 JavaScript 的超集，其编码规约与《JavaScript 编码规约》一脉相承。本节仅列出 TypeScript 特有的规范，其他编码风格默认遵循 JavaScript 规约。

### 2.1 重载函数

**【强制】重载的函数必须写在一起。** ESLint: `@typescript-eslint/adjacent-overload-signatures`

自然相关的项组合在一起将提高代码可读性和组织性。

**反例：**
```typescript
// ❌ 错误：重载函数分散
declare namespace Foo {
  export function foo(s: string): void;
  export function foo(n: number): void;
  export function bar(): void;
  export function foo(sn: string | number): void;
}
```

**正例：**
```typescript
// ✅ 正确：重载函数写在一起
declare namespace Foo {
  export function foo(s: string): void;
  export function foo(n: number): void;
  export function foo(sn: string | number): void;
  export function bar(): void;
}
```

### 2.2 数组类型

**【推荐】简单数组类型的定义使用 `T[]`，复杂类型使用 `Array<T>`。** ESLint: `@typescript-eslint/array-type`

对数组类型的定义使用相同的规范将帮助开发者更快地理解和阅读类型。

**反例：**
```typescript
// ❌ 错误：类型标注混乱
const a: (string | number)[] = ['a', 1];
const b: { prop: string }[] = [{ prop: 'a' }];
const c: (() => void)[] = [() => {}];
const d: Array<string> = ['a', 'b']; // 简单类型用了 Array<T>
```

**正例：**
```typescript
// ✅ 正确：简单类型用 T[]，复杂类型用 Array<T>
const a: Array<string | number> = ['a', 1];
const b: Array<{ prop: string }> = [{ prop: 'a' }];
const c: Array<() => void> = [() => {}];
const d: string[] = ['a', 'b'];
```

### 2.3 类型与接口

**【推荐】优先使用 `interface` 定义对象类型，使用 `type` 定义联合类型、交叉类型等。** ESLint: `@typescript-eslint/consistent-type-definitions`

**反例：**
```typescript
// ❌ 错误：对象类型使用 type
type User = {
  id: number;
  name: string;
};
```

**正例：**
```typescript
// ✅ 正确：对象类型使用 interface
interface User {
  id: number;
  name: string;
}

// ✅ 正确：联合类型使用 type
type Status = 'active' | 'inactive' | 'pending';
```

### 2.4 类型断言

**【强制】使用 `as Type` 进行类型断言，禁止使用 `<Type>` 语法。** ESLint: `@typescript-eslint/consistent-type-assertions`

**反例：**
```typescript
// ❌ 错误：使用尖括号语法
const value = <string>someValue;
```

**正例：**
```typescript
// ✅ 正确：使用 as 语法
const value = someValue as string;
```

### 2.5 禁止使用 any

**【强制】禁止使用 `any` 类型，应使用具体类型或 `unknown`。** ESLint: `@typescript-eslint/no-explicit-any`

**反例：**
```typescript
// ❌ 错误：使用 any
function process(data: any): any {
  return data;
}
```

**正例：**
```typescript
// ✅ 正确：使用具体类型或 unknown
function process<T>(data: T): T {
  return data;
}

function safeProcess(data: unknown): unknown {
  if (typeof data === 'string') {
    return data.toUpperCase();
  }
  return data;
}
```


## 三、React 编码规范

### 3.1 JSX 缩进

**【强制】JSX 语法使用 2 个空格缩进。** ESLint: `react/jsx-indent`, `react/jsx-indent-props`

对于 JSX 语法，遵循与 JS 规约和 HTML 规约一致的 2 个空格缩进，不要使用 4 空格或 tab 缩进。

**反例：**
```jsx
// ❌ 错误：使用 4 个空格缩进
return (
    <div>
        <h1>Title</h1>
        <p>Content</p>
    </div>
);
```

**正例：**
```jsx
// ✅ 正确：使用 2 个空格缩进
return (
  <div>
    <h1>Title</h1>
    <p>Content</p>
  </div>
);
```

### 3.2 JSX 空格

**【强制】自闭合标签的斜线前有且仅有一个空格。** ESLint: `no-multi-spaces`, `react/jsx-tag-spacing`

**反例：**
```jsx
// ❌ 错误
<Component/>   // 斜线前无空格
<Component  /> // 斜线前空格过多
```

**正例：**
```jsx
// ✅ 正确
<Component />
```

**【强制】JSX 行内属性之间仅有一个空格。** ESLint: `react/jsx-props-no-multi-spaces`

**反例：**
```jsx
// ❌ 错误
<Component  name="John"  age={30} />
```

**正例：**
```jsx
// ✅ 正确
<Component name="John" age={30} />
```

**【强制】JSX 属性的大括号内部两侧无空格。** ESLint: `react/jsx-curly-spacing`

**反例：**
```jsx
// ❌ 错误
<Component name={ userName } />
```

**正例：**
```jsx
// ✅ 正确
<Component name={userName} />
```

### 3.3 JSX 引号

**【强制】JSX 属性使用双引号，不要使用单引号。** ESLint: `jsx-quotes`

HTML 属性通常使用双引号而不是单引号，因此 JSX 属性沿用了这种约定。其他 JS 代码使用单引号。

**反例：**
```jsx
// ❌ 错误：属性使用单引号
<Component name='John' />
```

**正例：**
```jsx
// ✅ 正确：属性使用双引号
<Component name="John" />
```

### 3.4 小括号

**【强制】多行的 JSX 标签需用小括号包裹。** ESLint: `react/jsx-wrap-multilines`

**反例：**
```jsx
// ❌ 错误：多行 JSX 未用括号包裹
render() {
  return <div>
    <h1>Title</h1>
  </div>;
}
```

**正例：**
```jsx
// ✅ 正确：多行 JSX 用小括号包裹
render() {
  return (
    <div>
      <h1>Title</h1>
    </div>
  );
}

// ✅ 正确：单行 JSX 无需括号
render() {
  return <div>Hello</div>;
}
```

### 3.5 标签规范

**【强制】无子元素的标签需写成自闭合标签。** ESLint: `react/self-closing-comp`

**反例：**
```jsx
// ❌ 错误
<Component></Component>
<Component children={null} />
```

**正例：**
```jsx
// ✅ 正确
<Component />
```

### 3.6 组件命名

**【强制】组件文件名和组件名使用帕斯卡命名法（PascalCase）。**

**反例：**
```jsx
// ❌ 错误
// 文件: userProfile.jsx
function userProfile() { }
```

**正例：**
```jsx
// ✅ 正确
// 文件: UserProfile.jsx
function UserProfile() { }
```

### 3.7 Hooks 规范

**【强制】只在最顶层使用 Hooks，不要在循环、条件或嵌套函数中调用 Hooks。**

**反例：**
```jsx
// ❌ 错误：条件调用 Hooks
function Component({ condition }) {
  if (condition) {
    useEffect(() => { }); // 错误
  }
}
```

**正例：**
```jsx
// ✅ 正确：Hooks 在顶层调用
function Component({ condition }) {
  useEffect(() => {
    if (condition) {
      // 逻辑放在 Hook 内部
    }
  });
}
```

**【强制】自定义 Hook 必须以 `use` 开头。**

**反例：**
```jsx
// ❌ 错误
function fetchData() { }
```

**正例：**
```jsx
// ✅ 正确
function useFetchData() { }
```


## 四、CSS 编码规范

本规约涉及 CSS 及其预编译语言（Sass、Less）的编码风格和最佳实践，部分规则可通过 stylelint 工具落地。

### 4.1 分号

**【强制】所有声明都应该以分号结尾，不能省略。** stylelint: `declaration-block-trailing-semicolon`

虽然 CSS 语法中最后一条声明的分号是可选的，但是使用分号可以增加代码的一致性和易用性。

**反例：**
```css
/* ❌ 错误：最后一条声明缺少分号 */
.selector {
  margin-top: 10px;
  padding-left: 15px
}
```

**正例：**
```css
/* ✅ 正确：每条声明都以分号结尾 */
.selector {
  margin-top: 10px;
  padding-left: 15px;
}
```

### 4.2 缩进

**【推荐】使用 2 个空格缩进，不要使用 4 个空格或 tab 缩进。** stylelint: `indentation`

**反例：**
```css
/* ❌ 错误：4 个空格缩进 */
.selector {
    padding-left: 15px;
}
```

**正例：**
```css
/* ✅ 正确：2 个空格缩进 */
.selector {
  padding-left: 15px;
}
```

### 4.3 空格

**【推荐】选择器和 `{` 之间保留一个空格。** stylelint: `block-opening-brace-space-before`

**反例：**
```css
/* ❌ 错误：无空格 */
.selector{
  padding-left: 15px;
}
```

**正例：**
```css
/* ✅ 正确：保留一个空格 */
.selector {
  padding-left: 15px;
}
```

**【推荐】属性名和 `:` 之前无空格，`:` 和属性值之间保留一个空格。** stylelint: `declaration-colon-space-before`, `declaration-colon-space-after`

**反例：**
```css
/* ❌ 错误：空格位置错误 */
.selector {
  margin-top : 10px;
  padding-left:15px;
}
```

**正例：**
```css
/* ✅ 正确 */
.selector {
  margin-top: 10px;
  padding-left: 15px;
}
```

### 4.4 选择器命名

**【推荐】使用连字符（kebab-case）命名 class 和 id，避免使用驼峰命名。**

**反例：**
```css
/* ❌ 错误：驼峰命名 */
.userProfile { }
#mainContent { }
```

**正例：**
```css
/* ✅ 正确：连字符命名 */
.user-profile { }
#main-content { }
```


## 五、Git 提交规范

### 5.1 提交信息格式

**【推荐】使用 Conventional Commits 格式编写提交信息。**

```
<type>(<scope>): <description>

[body]

[footer]
```

### 5.2 Type 类型

type 用来描述本次提交的改动类型，可选值及对应含义如下：

| Type | 说明 |
|------|------|
| `feat` | 新增功能 |
| `fix` | 修复 bug |
| `docs` | 文档相关的改动 |
| `style` | 代码格式化改动，逻辑未变化（如缩进、分号等） |
| `test` | 新增或修改测试用例 |
| `refactor` | 重构代码或其他优化举措 |
| `chore` | 项目工程方面的改动，代码逻辑未变化 |
| `revert` | 恢复之前的提交 |

### 5.3 Scope

scope 用来描述本次提交所涉及到的改动范围（例如模块、功能或其他任何限定的范围）。scope 的具体取值视项目而定。

### 5.4 Description 规范

**【强制】时态方面使用一般现在时，不要使用过去时。** 虽然查看 message 时内容本身都发生在过去，然而对于主题来说，使用现在时的时态更简洁明确。

**【强制】句式使用祈使句。** 即一般情况不要增加主语，因为绝大情况下主语都是作者『我』。

**【强制】句首无需大写，句尾无需结束标点。** 因为主题（或标题）本身不用形成完整的句子。

**反例：**
```bash
# ❌ 错误：过去时、完整句子、首字母大写
git commit -m "Docs: Deleted redundant docs."
git commit -m "feat: I add user login function"
```

**正例：**
```bash
# ✅ 正确：一般现在时、祈使句、小写、无句号
git commit -m "docs: delete redundant docs"
git commit -m "feat: add user login function"
git commit -m "fix(user): correct email validation logic"
```

### 5.5 语言规范

- 国际化或开源项目，推荐使用英文编写提交信息，便于协作。
- 其他项目，推荐使用团队成员最熟悉的语言，以提高效率。
- 禁止使用其他团队成员无法理解的语言。
- 禁止使用拼音或不常见的缩写，可能导致误解。


## 六、工程规范

### 6.1 文档通用规约

F2E-Spec 提供了文档通用规约，配合 markdownlint 工具使用。主要规则包括：

- 标题层级正确，不跳级
- 代码块指定语言
- 列表缩进一致
- 文件末尾保留一个空行

### 6.2 更新日志规约

更新日志应遵循以下规范：
- 按版本号组织
- 分类记录改动类型（feat、fix、breaking changes 等）
- 使用清晰的语言描述改动内容
- 标注贡献者信息


## 七、配套工具

### 7.1 工具概览

| 规约 | 工具 | 规则包 |
|------|------|--------|
| JavaScript/TypeScript/React/Node.js | ESLint | eslint-config-ali |
| CSS | stylelint | stylelint-config-ali |
| Git 提交信息 | commitlint | commitlint-config-ali |
| 文档 | markdownlint | markdownlint-config-ali |

### 7.2 F2ELint 一键接入

由于各个 Linter 和规则包众多且零散，全部安装会给项目增加十几个依赖，接入及后续升级成本较高。因此 F2E-Spec 封装了 F2ELint 这个 npm 包，它收敛屏蔽了这些依赖和配置细节，提供简单的 CLI 和 Node.js API，让项目能够一键接入、一键扫描、一键修复、一键升级，并为项目配置 git commit 卡口。

**快速接入：**

```bash
# 安装 F2ELint
npm install f2elint -D

# 初始化配置（自动生成所有配置文件）
npx f2elint init

# 扫描代码问题
npx f2elint scan

# 自动修复可修复的问题
npx f2elint fix
```

### 7.3 ESLint 配置示例

```javascript
// .eslintrc.js
module.exports = {
  extends: ['eslint-config-ali'],
  // 可根据项目需要覆盖部分规则
  rules: {
    // 自定义规则
  }
};
```

### 7.4 stylelint 配置示例

```javascript
// .stylelintrc.js
module.exports = {
  extends: ['stylelint-config-ali'],
  rules: {
    // 自定义规则
  }
};
```

### 7.5 commitlint 配置示例

```javascript
// .commitlintrc.js
module.exports = {
  extends: ['commitlint-config-ali']
};
```


## 八、快速检查清单

### JavaScript/TypeScript
- [ ] 是否使用 2 个空格缩进？
- [ ] 语句是否以分号结尾？
- [ ] 多行结构是否添加了 trailing comma？
- [ ] 字符串是否使用单引号？
- [ ] 是否使用 `const`/`let` 替代 `var`？
- [ ] 是否使用 `===` 和 `!==` 进行比较？
- [ ] 变量/函数是否使用 camelCase，类名是否使用 PascalCase？
- [ ] TypeScript 是否禁止使用 `any` 类型？

### React
- [ ] JSX 是否使用 2 个空格缩进？
- [ ] JSX 属性是否使用双引号？
- [ ] 多行 JSX 是否用小括号包裹？
- [ ] 无子元素的标签是否写成自闭合？
- [ ] 组件名是否使用 PascalCase？
- [ ] Hooks 是否只在顶层调用？

### CSS
- [ ] 每条声明是否以分号结尾？
- [ ] 是否使用 2 个空格缩进？
- [ ] 选择器和 `{` 之间是否有空格？
- [ ] class 命名是否使用 kebab-case？

### Git
- [ ] 提交信息是否符合 Conventional Commits 格式？
- [ ] type 是否正确使用？
- [ ] 描述是否使用一般现在时和祈使句？

### 工程
- [ ] 是否接入 F2ELint 或相应的 Linter 规则包？
- [ ] 是否配置了 git commit 卡口？


## 九、参考资料

- [阿里巴巴前端规约 GitHub 仓库](https://github.com/alibaba/f2e-spec)
- [JavaScript 编码规约](https://github.com/alibaba/f2e-spec/blob/main/docs/coding/javascript.md)
- [TypeScript 编码规约](https://github.com/alibaba/f2e-spec/blob/main/docs/coding/typescript.md)
- [React 编码规约](https://github.com/alibaba/f2e-spec/blob/main/docs/coding/react.md)
- [CSS 编码规约](https://github.com/alibaba/f2e-spec/blob/main/docs/coding/css.md)
- [Git 规约](https://github.com/alibaba/f2e-spec/blob/main/docs/engineering/git.md)
- [F2ELint npm 包](https://www.npmjs.com/package/f2elint)
- [eslint-config-ali](https://www.npmjs.com/package/eslint-config-ali)
- [stylelint-config-ali](https://www.npmjs.com/package/stylelint-config-ali)
- [commitlint-config-ali](https://www.npmjs.com/package/commitlint-config-ali)

---

本规范基于阿里巴巴前端规约（F2E-Spec）官方文档整理，建议在实际项目中通过 F2ELint 工具自动化落地。规范中的约束等级和具体规则可根据团队实际情况进行调整。